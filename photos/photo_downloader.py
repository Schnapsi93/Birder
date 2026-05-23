import os
import json
import time
import requests
from io import BytesIO
from PIL import Image, ImageOps
from concurrent.futures import ThreadPoolExecutor, as_completed
from bs4 import BeautifulSoup
from tqdm import tqdm
import random


import requests

session = requests.Session()

session.headers.update({
    "User-Agent": "BirdImageDatasetBot/1.0 (contact: youremail@example.com)",
    "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
    "Referer": "https://commons.wikimedia.org/"
})

WIKI_API = "https://en.wikipedia.org/api/rest_v1/page/summary/"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"

CACHE_DIR = "images_cache"
OUT_JSON = "output/metadata_with_images.json"
os.makedirs(CACHE_DIR, exist_ok=True)
os.makedirs("output", exist_ok=True)

HEADERS = {
    "User-Agent": "BirdImageDatasetBot/1.0 (contact: youremail@example.com)",
    "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
    "Referer": "https://commons.wikimedia.org/"
}


# ----------------------------
# UTIL: image filtering
# ----------------------------
def is_bad_image(url: str) -> bool:
    bad_keywords = ["icon", "logo", "map", "symbol", "svg", "coat_of_arms"]
    return any(k in url.lower() for k in bad_keywords)


# ----------------------------
# Wikipedia image fetch
# ----------------------------
def get_wikipedia_image(scientific_name):
    url = WIKI_API + scientific_name.replace(" ", "_")

    try:
        r = session.get(url, timeout=10)

        if r.status_code != 200:
            return None, f"Wikipedia HTTP {r.status_code}"

        data = r.json()

        if "originalimage" in data:
            return data["originalimage"]["source"], None

        if "thumbnail" in data:
            return data["thumbnail"]["source"], "Wikipedia only thumbnail"

        return None, "No image in Wikipedia page"

    except Exception as e:
        return None, f"Wikipedia exception: {str(e)}"


# ----------------------------
# Wikimedia Commons fallback
# ----------------------------
def get_commons_image(query):
    try:
        url = "https://commons.wikimedia.org/w/api.php"

        params = {
            "action": "query",
            "format": "json",
            "generator": "search",
            "gsrsearch": query + " bird",
            "gsrlimit": 5,
            "prop": "imageinfo",
            "iiprop": "url"
        }

        r = session.get(url, params=params, timeout=10)

        if r.status_code == 403:
            return None, "Commons blocked (403) → missing UA or rate limit"

        if r.status_code != 200:
            return None, f"Commons HTTP {r.status_code}"

        data = r.json()

        if "query" not in data:
            return None, "Commons: no results"

        for page in data["query"]["pages"].values():
            if "imageinfo" in page:
                img = page["imageinfo"][0]["url"]
                if not is_bad_image(img):
                    return img, None

        return None, "Commons: no valid images"

    except Exception as e:
        return None, f"Commons exception: {str(e)}"

# ----------------------------
# Download image with cache
# ----------------------------
def download_image(url, species):
    time.sleep(0.1 + random.random() * 0.2)
    if not url:
        return None, "No URL provided"

    filename = species.replace(" ", "_") + ".jpg"
    path = os.path.join(CACHE_DIR, filename)

    if os.path.exists(path):
        return path, None

    try:
        r = session.get(url, timeout=20, stream=True)

        if r.status_code == 403:
            return None, "Image blocked (403) → missing Referer/User-Agent"

        if r.status_code != 200:
            return None, f"Download HTTP {r.status_code}"

        content_type = r.headers.get("Content-Type", "")

        if "image" not in content_type:
            return None, f"Not an image: {content_type}"

        img = Image.open(BytesIO(r.content)).convert("RGB")
        img = process_image(img)

        img.save(path, "JPEG", quality=90)

        return path, None

    except Exception as e:
        return None, f"Download exception: {str(e)}"

# ----------------------------
# Resize: crop + pad to 200x200
# ----------------------------
def process_image(img):
    target_size = (200, 200)

    # fit image while keeping aspect ratio
    img = ImageOps.contain(img, target_size)

    # create white background
    canvas = Image.new("RGB", target_size, (255, 255, 255))

    # center
    x = (200 - img.width) // 2
    y = (200 - img.height) // 2
    canvas.paste(img, (x, y))

    return canvas


# ----------------------------
# Pipeline per species
# ----------------------------
def process_species(species, entry, idx, total):
    log(species, f"START {idx}/{total}")

    # Wikipedia
    url, err = get_wikipedia_image(species)

    if err:
        log(species, f"Wikipedia issue: {err}")

    # Commons fallback
    if not url:
        url, err2 = get_commons_image(species)

        if err2:
            log(species, f"Commons issue: {err2}")

    if not url:
        log(species, "FAILED: no image source found")
        return species, None, "no_source"

    # Download
    path, err3 = download_image(url, species)

    if err3:
        log(species, f"DOWNLOAD FAIL: {err3}")
        return species, None, err3

    log(species, f"SUCCESS → {path}")
    return species, path, None

def log(species, msg):
    print(f"[{species}] {msg}")


# ----------------------------
# MAIN
# ----------------------------
def main():
    

    
    with open("metadata.json", "r", encoding="utf-8") as f:
        data = json.load(f)

    species_list = list(data.items())
    total = len(species_list)

    results = {}
    start_all = time.time()

    with ThreadPoolExecutor(max_workers=3) as executor:
        futures = [
            executor.submit(process_species, species, entry, i + 1, total)
            for i, (species, entry) in enumerate(species_list)
        ]

        for f in tqdm(as_completed(futures), total=total):
            species, path, elapsed = f.result()
            results[species] = path
            

            done = len(results)
            speed = done / (time.time() - start_all + 1e-6)

            print(f"✔ {species} → {path} | {done}/{total} | {speed:.2f} img/s")
            
            
            results = {}
            results_errors = {}
            
            species, path, err = f.result()
            results[species] = path
            results_errors[species] = err

    # merge into original JSON
    for species in data:
        path = results.get(species)
    
        data[species]["image_path"] = path
    
        if not path:
            data[species]["image_error"] = results_errors.get(species, "unknown")
    
        with open(OUT_JSON, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)

    print("\nDone:", OUT_JSON)

    


if __name__ == "__main__":
    main()