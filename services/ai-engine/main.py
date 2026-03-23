from fastapi import FastAPI

app = FastAPI(title="History Graph AI Engine")


@app.get("/health")
def health():
    return {"status": "ok"}
