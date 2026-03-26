import requests
import numpy as np

# 1. 基础配置
OLLAMA_URL = "http://localhost:11434/api/embeddings"
GENERATE_URL = "http://localhost:11434/api/generate"

def cosine_similarity(v1, v2):
    return np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))

def get_embedding(text, model="bge-m3"):
    try:
        payload = {"model": model, "prompt": text}
        # 增加 timeout 防止模型加载时卡死
        response = requests.post(OLLAMA_URL, json=payload, timeout=60)
        response.raise_for_status()
        return np.array(response.json()['embedding'])
    except Exception as e:
        print(f"Error calling Embedding API: {e}")
        return None

# 2. 模拟数据库
documents = [
    {"id": 1, "content": "华为Mate 60 Pro采用了自研的麒麟9000s芯片，支持卫星通话功能。"},
    {"id": 2, "content": "红富士苹果原产于日本，口感清脆甜美，含有丰富的维生素C。"}
]

# 3. 预计算向量
print("正在索引文档...")
for doc in documents:
    vec = get_embedding(doc['content'])
    if vec is None:
        exit("无法获取文档向量，请检查 Ollama 服务是否启动并已拉取 bge-m3 模型")
    doc['vector'] = vec

# 4. 用户提问
query = "最近有什么支持卫星通话的国产手机推荐？"
query_vector = get_embedding(query)

# 5. 检索阶段
best_doc = max(documents, key=lambda d: cosine_similarity(d['vector'], query_vector))
print(f"\n--- 检索到的最相关文档 ---\n{best_doc['content']}\n")

# 6. 生成阶段
print("正在生成回答...")
prompt = f"请根据以下已知内容回答问题：\n内容：{best_doc['content']}\n问题：{query}"
gen_res = requests.post(GENERATE_URL, json={
    "model": "deepseek-r1:1.5b", 
    "prompt": prompt, 
    "stream": False
}, timeout=120)
print(f"--- DeepSeek 回答 ---\n{gen_res.json()['response']}")

