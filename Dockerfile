FROM pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

# ffmpeg（含 libass 字幕燒錄）、中日韓字型；gcc 用來編譯 audio-separator 依賴的 diffq（沒有預編 wheel）
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg fonts-noto-cjk fontconfig ca-certificates gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

# yt-dlp 解析 YouTube 需要 JavaScript runtime（deno 2.3 以上）
COPY --from=denoland/deno:bin-2.5.6 /deno /usr/local/bin/deno

WORKDIR /app
# 用鎖定版本安裝，日後重建也能得到同一組套件；yt-dlp 例外，每次建置都更新
COPY requirements.lock.txt .
RUN pip install -r requirements.lock.txt
RUN pip install -U "yt-dlp[default]"

# 動態歌詞字型：霞鶩文楷 TC（SIL OFL 1.1，可商用）；放在 pip 之後，改字型不必重裝套件
ADD https://github.com/lxgw/LxgwWenkaiTC/releases/download/v1.530/LXGWWenKaiTC-Medium.ttf /usr/share/fonts/truetype/lxgw/
RUN chmod 644 /usr/share/fonts/truetype/lxgw/*.ttf && fc-cache -f

COPY app/ /app/

# 模型、快取都放在 volume，重建映像不必重新下載
ENV OUTPUT_DIR=/data/output \
    MODEL_DIR=/data/models \
    XDG_CACHE_HOME=/data/models/cache \
    HF_HOME=/data/models/cache/huggingface \
    TORCH_HOME=/data/models/cache/torch

ENTRYPOINT ["python", "/app/cli.py"]
