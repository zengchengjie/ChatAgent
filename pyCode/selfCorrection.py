import requests
import json
import re

# 1. 模拟严格的后端 API
def get_user_balance(user_id):
    # 后端严格校验：必须是 6 位数字字符串
    if not user_id or not str(user_id).isdigit() or len(str(user_id)) != 6:
        raise ValueError(f"ID '{user_id}' 格式错误。要求：必须是 6 位纯数字。")
    return {"user_id": user_id, "balance": 1000}

# 2. 后端辅助工具：正则“抠” JSON
def extract_json(text):
    try:
        # 尝试匹配最外层的 { ... }
        match = re.search(r'\{.*\}', text, re.DOTALL)
        if not match: return None
        clean_str = match.group()
        # 常见修复：把单引号替换为双引号，这是小模型最爱犯的错
        clean_str = clean_str.replace("'", '"')
        return json.loads(clean_str)
    except:
        return None

def chat_with_ds(messages):
    url = "http://localhost:11434/api/chat"
    # 提示词微调：给它一个明确的 Example (One-Shot)
    payload = {
        "model": "deepseek-r1:1.5b",
        "messages": messages,
        "stream": False,
        "options": {"temperature": 0.3} # 降低随机性，让它别乱写
    }
    res = requests.post(url, json=payload)
    return res.json()['message']

# --- 主逻辑 ---
system_prompt = """你是一个银行助手。
当用户要查余额，你必须仅返回一个 JSON 格式，例如：{"action": "get_balance", "user_id": "123456"}
不要有任何多余的废话。"""

messages = [
    {"role": "system", "content": system_prompt},
    {"role": "user", "content": "帮我查一下用户 ID 是 'ABC-123' 的余额。"}
]

for i in range(3):
    print(f"\n[尝试 {i+1}] 思考中...")
    response = chat_with_ds(messages)
    content = response['content']
    print(f"原始输出: {content}")
    
    data = extract_json(content)
    
    if data and "user_id" in data:
        try:
            result = get_user_balance(data["user_id"])
            print(f"✅ 成功! 余额为: {result['balance']}")
            break
        except Exception as e:
            error_msg = str(e)
            print(f"❌ 后端逻辑报错: {error_msg}")
            feedback = f"参数错误：{error_msg}。请修正为 6 位数字再试。"
    else:
        error_msg = "无法解析你的 JSON 格式"
        print(f"❌ 解析报错: {error_msg}")
        feedback = "你的返回格式不是标准的 JSON。请只返回类似 {\"action\": \"get_balance\", \"user_id\": \"123456\"} 的内容。"

    messages.append(response)
    messages.append({"role": "user", "content": feedback})
else:
    print("\n[结果] 失败次数过多。")

