"""Renders original and translated PDFs side by side: compare.py orig.pdf trans.pdf out_prefix [dpi]"""
import sys, pymupdf
from PIL import Image
a, b = pymupdf.open(sys.argv[1]), pymupdf.open(sys.argv[2])
dpi = int(sys.argv[4]) if len(sys.argv) > 4 else 70
for i in range(len(a)):
    ia = a[i].get_pixmap(dpi=dpi); ib = b[i].get_pixmap(dpi=dpi)
    A = Image.frombytes("RGB", (ia.width, ia.height), ia.samples); B = Image.frombytes("RGB", (ib.width, ib.height), ib.samples)
    s = Image.new("RGB", (A.width + B.width + 10, max(A.height, B.height)), "grey"); s.paste(A, (0, 0)); s.paste(B, (A.width + 10, 0))
    s.save(f"{sys.argv[3]}{i:02d}.png")
