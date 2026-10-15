"""Cheap, conservative image comparison before optional scene VLM analysis."""

from PIL import Image, ImageOps

WIDTH, HEIGHT = 48, 36


def pixels(path):
    with Image.open(path) as source:
        source.draft('RGB', (96, 72))  # JPEG decoder can avoid full-resolution work.
        image = ImageOps.exif_transpose(source).convert('RGB').resize(
            (WIDTH, HEIGHT), Image.Resampling.BILINEAR)
        return list(image.getdata())


def near_duplicate(first, second):
    """Only suppress near-identical views; a local object change must survive."""
    if len(first) != WIDTH * HEIGHT or len(second) != len(first):
        return False
    offsets = [sum(a[c] - b[c] for a, b in zip(first, second)) / len(first)
               for c in range(3)]
    if any(abs(value) > 20 for value in offsets):
        return False
    total = 0.0
    largest_patch = 0.0
    for top in range(0, HEIGHT, 6):
        for left in range(0, WIDTH, 6):
            patch = 0.0
            for y in range(top, top + 6):
                for x in range(left, left + 6):
                    a, b = first[y * WIDTH + x], second[y * WIDTH + x]
                    patch += sum(abs(a[c] - b[c] - offsets[c]) for c in range(3)) / 3
            total += patch
            largest_patch = max(largest_patch, patch / 36)
    return total / (WIDTH * HEIGHT) < 9 and largest_patch < 20


def matching_source(path, recent_sources):
    candidate = pixels(path)
    for source_id, source_path in recent_sources:
        try:
            if near_duplicate(candidate, pixels(source_path)):
                return source_id
        except (OSError, ValueError):
            continue
    return None
