export type SseHandler = (eventName: string, data: unknown) => void

/**
 * 解析 Spring SseEmitter 推送的字节流：事件块以「空行」分隔，块内常见 `event:` 与 `data:`（JSON）。
 * 与浏览器原生 EventSource（仅 GET）不同，此处配合 fetch POST 使用。
 */
export async function consumeSse(response: Response, onEvent: SseHandler): Promise<void> {
  if (!response.body) {
    throw new Error('No response body')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      dispatchBlock(block, onEvent)
    }
  }
}

/** 单个 SSE 块：默认事件名为 message；data 多行会拼接后再 JSON.parse。 */
function dispatchBlock(block: string, onEvent: SseHandler): void {
  let eventName = 'message'
  const dataLines: string[] = []
  for (const raw of block.split('\n')) {
    const line = raw.trimEnd()
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (dataLines.length === 0) {
    return
  }
  const payload = dataLines.join('\n')
  try {
    onEvent(eventName, JSON.parse(payload))
  } catch {
    onEvent(eventName, payload)
  }
}
