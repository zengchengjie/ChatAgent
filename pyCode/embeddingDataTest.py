import requests
import numpy as np

def get_embedding(text):
    url = "http://localhost:11434/api/embeddings"
    data = {"model": "deepseek-r1:1.5b", "prompt": text}
    res = requests.post(url, json=data)
    return np.array(res.json()['embedding'])

def cosine_similarity(v1, v2):
    return np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))

# 获取三个词的向量
v_apple = get_embedding("苹果")
v_phone = get_embedding("手机")
v_banana = get_embedding("香蕉")
v1 = get_embedding("华为手机")
v2 = get_embedding("手机")
print(f"华为手机 vs 手机 的相似度: {cosine_similarity(v1, v2):.4f}")
print(f"苹果 vs 手机 的相似度: {cosine_similarity(v_apple, v_phone):.4f}")
print(f"苹果 vs 香蕉 的相似度: {cosine_similarity(v_apple, v_banana):.4f}")

