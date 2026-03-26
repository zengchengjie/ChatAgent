import requests
import numpy as np

def get_embedding(text, model="bge-m3"):
    url = "http://localhost:11434/api/embeddings"
    res = requests.post(url, json={"model": model, "prompt": text})
    return np.array(res.json()['embedding'])

def cosine_similarity(v1, v2):
    return np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2))

# 测试 BGE-M3 的识别能力
v_iphone = get_embedding("iphone15 pro max")
v_phone = get_embedding("手机")
v_apple = get_embedding("苹果")
v_banana = get_embedding("香蕉")

print(f"--- BGE-M3 模型表现 ---")
print(f"维度: {len(v_iphone)}")
print(f"iphone15 pro max vs 手机: {cosine_similarity(v_iphone, v_phone):.4f}")
print(f"苹果 vs 手机: {cosine_similarity(v_apple, v_phone):.4f}")
print(f"苹果 vs 香蕉: {cosine_similarity(v_apple, v_banana):.4f}")

