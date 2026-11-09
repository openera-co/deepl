"""Generate a large, varied corpus of French school documents for testing Carnet.

Every document gets a sidecar <name>.json with ground-truth facts the test harness checks
after translation (paragraph counts, tables, images, formatted spans...).

usage: python generate.py <out_dir> [--docx N] [--pptx N] [--xlsx N] [--odt N] [--pdf N] [--img N] [--txt N]
"""
import argparse, io, json, os, random, re, sys
from multiprocessing import Pool
from pathlib import Path

HERE = Path(__file__).parent
FONTS_DIR = "/usr/share/fonts/truetype"


def load_text():
    school = [l.strip() for l in open(HERE / "school_fr.txt", encoding="utf-8") if l.strip()]
    paras = list(school)
    wiki = Path("/opt/data/text/wiki_fr.json")
    if wiki.exists():
        paras += [p["text"] for p in json.load(open(wiki, encoding="utf-8"))]
    news = Path("/opt/data/eval/newsdiscuss2015.fr")
    if news.exists():
        paras += [l for l in open(news, encoding="utf-8").read().splitlines() if 40 < len(l) < 400]
    return school, paras


SCHOOL, PARAS = load_text()
SUBJECTS = ["Mathématiques", "Physique-chimie", "SVT", "Histoire-géographie", "Français", "Philosophie",
            "Anglais", "EMC", "Technologie", "Économie", "Musique", "Arts plastiques", "EPS"]
TITLES = ["Chapitre {n} : {s}", "Fiche de révision – {s}", "Contrôle n°{n}", "Exercices corrigés", "Devoir maison {n}",
          "Leçon {n} : notions essentielles", "Compte rendu de TP", "Cours de {s}", "Évaluation de fin de trimestre", "Exposé : {s}"]
HEAD_TOPICS = ["Introduction", "Définitions", "À retenir", "Exemple", "Méthode", "Exercice {n}", "Conclusion",
               "Pour aller plus loin", "Vocabulaire", "Questions", "Correction", "Rappel du cours"]
FONTS = ["Calibri", "Times New Roman", "Arial", "Cambria", "Georgia", "Verdana", "Comic Sans MS", "Garamond"]


def rnd_title(r):
    return r.choice(TITLES).format(n=r.randint(1, 12), s=r.choice(SUBJECTS))


def rnd_par(r, short=False):
    if short or r.random() < 0.55:
        k = r.randint(1, 3)
        return " ".join(r.choice(SCHOOL) for _ in range(k))
    return r.choice(PARAS)


def pick_span(r, text):
    """Choose a word-aligned span (1-3 words) inside text to format."""
    words = list(re.finditer(r"[A-Za-zÀ-ÖØ-öø-ÿœŒ'’-]{4,}", text))
    if not words:
        return None
    i = r.randrange(len(words))
    j = min(len(words) - 1, i + r.choice([0, 0, 0, 1, 2]))
    return words[i].start(), words[j].end()


def make_image(r, w=480, h=300):
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (w, h), tuple(r.randint(180, 255) for _ in range(3)))
    d = ImageDraw.Draw(img)
    for _ in range(r.randint(3, 8)):
        x0, y0 = r.randint(0, w - 40), r.randint(0, h - 40)
        x1, y1 = x0 + r.randint(20, 200), y0 + r.randint(20, 150)
        col = tuple(r.randint(0, 255) for _ in range(3))
        (d.ellipse if r.random() < 0.5 else d.rectangle)([x0, y0, x1, y1], fill=col)
    b = io.BytesIO(); img.save(b, "PNG"); b.seek(0)
    return b


# ---------------------------------------------------------------- DOCX
def gen_docx(path, seed):
    import docx
    from docx.shared import Pt, RGBColor, Inches
    from docx.enum.text import WD_COLOR_INDEX, WD_ALIGN_PARAGRAPH
    from docx.oxml.ns import qn
    from docx.oxml import OxmlElement
    r = random.Random(seed)
    d = docx.Document()
    font = r.choice(FONTS)
    st = d.styles["Normal"]; st.font.name = font; st.font.size = Pt(r.choice([10, 11, 12]))
    rpr = st.element.get_or_add_rPr(); lang = OxmlElement("w:lang"); lang.set(qn("w:val"), "fr-FR"); rpr.append(lang)
    meta = {"kind": "docx", "seed": seed, "spans": [], "tables": 0, "images": 0, "paragraphs": 0, "hyperlinks": 0}
    d.add_heading(rnd_title(r), 0); meta["paragraphs"] += 1
    sec = d.sections[0]
    sec.header.paragraphs[0].text = f"{r.choice(SUBJECTS)} – Classe de {r.choice(['6e','5e','4e','3e','seconde','première','terminale'])}"
    fp = sec.footer.paragraphs[0]; fp.text = "Page "
    run = fp.add_run()
    for tag, txt in (("begin", None), (None, "PAGE"), ("separate", None), (None, None), ("end", None)):
        if tag:
            fc = OxmlElement("w:fldChar"); fc.set(qn("w:fldCharType"), tag); run._r.append(fc)
        elif txt:
            it = OxmlElement("w:instrText"); it.text = txt; run._r.append(it)
        else:
            t = OxmlElement("w:t"); t.text = "1"; run._r.append(t)
    for _ in range(r.randint(3, 9)):
        d.add_heading(r.choice(HEAD_TOPICS).format(n=r.randint(1, 9)), r.choice([1, 2, 3])); meta["paragraphs"] += 1
        for _ in range(r.randint(1, 4)):
            choice = r.random()
            if choice < 0.6:
                text = rnd_par(r)
                p = d.add_paragraph(); meta["paragraphs"] += 1
                p.alignment = r.choice([None, WD_ALIGN_PARAGRAPH.JUSTIFY, WD_ALIGN_PARAGRAPH.LEFT])
                sp = pick_span(r, text) if r.random() < 0.7 else None
                if r.random() < 0.15:  # label-style bold prefix
                    lab = r.choice(["Définition", "Remarque", "Attention", "Exemple", "À retenir", "Consigne"]) + " : "
                    rr = p.add_run(lab); rr.bold = True
                    meta["spans"].append({"text": lab.strip(), "fmt": "bold"})
                if sp:
                    a, b = sp
                    p.add_run(text[:a])
                    fr = p.add_run(text[a:b])
                    fmt = r.choice(["bold", "italic", "underline", "color", "highlight", "bolditalic"])
                    if fmt in ("bold", "bolditalic"): fr.bold = True
                    if fmt in ("italic", "bolditalic"): fr.italic = True
                    if fmt == "underline": fr.underline = True
                    if fmt == "color": fr.font.color.rgb = RGBColor(r.randint(0, 200), 0, r.randint(0, 200))
                    if fmt == "highlight": fr.font.highlight_color = WD_COLOR_INDEX.YELLOW
                    p.add_run(text[b:])
                    meta["spans"].append({"text": text[a:b], "fmt": fmt})
                else:
                    p.add_run(text)
                if r.random() < 0.1:
                    p.add_run().add_break(); p.add_run(r.choice(SCHOOL))
                if r.random() < 0.08:
                    # hyperlink
                    hl = OxmlElement("w:hyperlink"); hl.set(qn("w:history"), "1")
                    rid = d.part.relate_to("https://fr.wikipedia.org/wiki/Photosynth%C3%A8se", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True)
                    hl.set(qn("r:id"), rid)
                    nr = OxmlElement("w:r"); t = OxmlElement("w:t"); t.text = " voir l'article complet"; t.set(qn("xml:space"), "preserve"); nr.append(t); hl.append(nr)
                    p._p.append(hl); meta["hyperlinks"] += 1
            elif choice < 0.75:
                style = r.choice(["List Bullet", "List Number"])
                for _ in range(r.randint(2, 5)):
                    d.add_paragraph(r.choice(SCHOOL), style=style); meta["paragraphs"] += 1
            elif choice < 0.88:
                rows, cols = r.randint(2, 6), r.randint(2, 4)
                t = d.add_table(rows=rows, cols=cols); t.style = "Table Grid"; meta["tables"] += 1
                heads = r.sample(["Nom", "Date", "Événement", "Formule", "Unité", "Définition", "Exemple", "Note", "Remarque", "Lieu"], cols)
                for c in range(cols):
                    cell = t.cell(0, c); cell.text = ""; cell.paragraphs[0].add_run(heads[c]).bold = True
                for i in range(1, rows):
                    for c in range(cols):
                        t.cell(i, c).text = r.choice([r.choice(SCHOOL)[:60], str(r.randint(1, 2000)), "CH₄ + 2 O₂", "v = d / t", "oui", "non"])
            else:
                d.add_picture(make_image(r), width=Inches(r.choice([2.5, 3.5, 5]))); meta["images"] += 1
                cap = d.add_paragraph("Figure : " + r.choice(SCHOOL)[:80]); meta["paragraphs"] += 1
                cap.runs[0].italic = True
    d.save(path)
    return meta


# ---------------------------------------------------------------- PPTX
def gen_pptx(path, seed):
    from pptx import Presentation
    from pptx.util import Inches, Pt
    from pptx.dml.color import RGBColor
    r = random.Random(seed)
    prs = Presentation()
    meta = {"kind": "pptx", "seed": seed, "slides": 0, "spans": [], "tables": 0, "images": 0, "notes": 0}
    s = prs.slides.add_slide(prs.slide_layouts[0]); meta["slides"] += 1
    s.shapes.title.text = rnd_title(r); s.placeholders[1].text = f"{r.choice(SUBJECTS)} – {r.choice(['Mme Martin', 'M. Dubois', 'Mme Leroy', 'M. Bernard'])}"
    for _ in range(r.randint(3, 12)):
        kind = r.random()
        if kind < 0.6:
            s = prs.slides.add_slide(prs.slide_layouts[1]); meta["slides"] += 1
            s.shapes.title.text = r.choice(HEAD_TOPICS).format(n=r.randint(1, 9))
            tf = s.placeholders[1].text_frame; tf.clear()
            for i in range(r.randint(2, 5)):
                p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
                text = r.choice(SCHOOL)
                sp = pick_span(r, text) if r.random() < 0.6 else None
                if sp:
                    a, b = sp
                    p.add_run().text = text[:a]
                    fr = p.add_run(); fr.text = text[a:b]; fr.font.bold = True
                    fr.font.color.rgb = RGBColor(0xC0, 0x30, 0x20) if r.random() < 0.5 else RGBColor(0, 0, 0)
                    p.add_run().text = text[b:]
                    meta["spans"].append({"text": text[a:b], "fmt": "bold"})
                else:
                    p.text = text
                p.level = r.choice([0, 0, 1])
        elif kind < 0.8:
            s = prs.slides.add_slide(prs.slide_layouts[5]); meta["slides"] += 1
            s.shapes.title.text = "Tableau récapitulatif"
            rows, cols = r.randint(2, 5), r.randint(2, 4)
            tbl = s.shapes.add_table(rows, cols, Inches(0.5), Inches(1.5), Inches(9), Inches(0.4 * rows)).table; meta["tables"] += 1
            for i in range(rows):
                for c in range(cols):
                    tbl.cell(i, c).text = r.choice(SCHOOL)[:50] if i else r.choice(["Notion", "Définition", "Exemple", "Date", "Auteur"])
        else:
            s = prs.slides.add_slide(prs.slide_layouts[5]); meta["slides"] += 1
            s.shapes.title.text = "Document " + str(r.randint(1, 5))
            s.shapes.add_picture(make_image(r), Inches(1), Inches(1.5), width=Inches(5)); meta["images"] += 1
            tb = s.shapes.add_textbox(Inches(6.2), Inches(1.5), Inches(3.5), Inches(3)).text_frame
            tb.word_wrap = True; tb.text = r.choice(SCHOOL)
        if r.random() < 0.4:
            s.notes_slide.notes_text_frame.text = "Note pour l'oral : " + r.choice(SCHOOL); meta["notes"] += 1
    prs.save(path)
    return meta


# ---------------------------------------------------------------- XLSX
def gen_xlsx(path, seed):
    import openpyxl
    from openpyxl.comments import Comment
    from openpyxl.cell.rich_text import CellRichText, TextBlock
    from openpyxl.cell.text import InlineFont
    r = random.Random(seed)
    wb = openpyxl.Workbook()
    meta = {"kind": "xlsx", "seed": seed, "sheets": 0, "formulas": [], "rich": 0, "protected": []}
    for si in range(r.randint(1, 3)):
        ws = wb.active if si == 0 else wb.create_sheet()
        ws.title = r.choice(["Notes", "Emploi du temps", "Résultats", "Inventaire", "Budget", "Présences"]) + ("" if si == 0 else f" {si}")
        meta["sheets"] += 1
        heads = r.sample(["Élève", "Matière", "Note", "Coefficient", "Appréciation", "Présent", "Date", "Remarque"], 5)
        for c, h in enumerate(heads, 1):
            ws.cell(1, c, h)
        n = r.randint(5, 40)
        for row in range(2, n + 2):
            ws.cell(row, 1, r.choice(["Léa", "Hugo", "Chloé", "Lucas", "Inès", "Nathan", "Manon", "Louis"]))
            ws.cell(row, 2, r.choice(SUBJECTS))
            ws.cell(row, 3, r.randint(0, 20))
            ws.cell(row, 4, r.choice(["Oui", "Non"]))
            ws.cell(row, 5, r.choice(["Très bon travail.", "Des efforts à poursuivre.", "Ensemble satisfaisant.", "Doit participer davantage à l'oral."]))
        f = f'=COUNTIF(D2:D{n+1},"Oui")'
        ws.cell(n + 3, 1, "Nombre de présents"); ws.cell(n + 3, 4, f); meta["formulas"].append(f); meta["protected"].append("Oui")
        ws.cell(n + 4, 1, "Moyenne de la classe"); ws.cell(n + 4, 3, f"=AVERAGE(C2:C{n+1})"); meta["formulas"].append(f"=AVERAGE(C2:C{n+1})")
        if r.random() < 0.5:
            ws.cell(n + 6, 1).value = CellRichText([TextBlock(InlineFont(b=True), "Attention : "), "les notes sont provisoires jusqu'au conseil de classe."]); meta["rich"] += 1
        if r.random() < 0.4:
            ws.cell(2, 5).comment = Comment("Vérifier cette appréciation avec le professeur principal.", "Prof")
    wb.save(path)
    return meta


# ---------------------------------------------------------------- ODT
def gen_odt(path, seed):
    from odf.opendocument import OpenDocumentText
    from odf.style import Style, TextProperties
    from odf.text import H, P, Span, List, ListItem
    from odf.table import Table, TableColumn, TableRow, TableCell
    r = random.Random(seed)
    doc = OpenDocumentText()
    bold = Style(name="Gras", family="text"); bold.addElement(TextProperties(fontweight="bold")); doc.automaticstyles.addElement(bold)
    ital = Style(name="Ital", family="text"); ital.addElement(TextProperties(fontstyle="italic")); doc.automaticstyles.addElement(ital)
    fr = Style(name="Standard", family="paragraph"); fr.addElement(TextProperties(language="fr", country="FR")); doc.styles.addElement(fr)
    meta = {"kind": "odt", "seed": seed, "spans": [], "paragraphs": 0, "tables": 0}
    doc.text.addElement(H(outlinelevel=1, text=rnd_title(r))); meta["paragraphs"] += 1
    for _ in range(r.randint(3, 10)):
        doc.text.addElement(H(outlinelevel=2, text=r.choice(HEAD_TOPICS).format(n=r.randint(1, 9)))); meta["paragraphs"] += 1
        for _ in range(r.randint(1, 3)):
            text = rnd_par(r)
            p = P(stylename=fr); meta["paragraphs"] += 1
            sp = pick_span(r, text) if r.random() < 0.7 else None
            if sp:
                a, b = sp
                p.addText(text[:a]); st = r.choice([bold, ital]); p.addElement(Span(stylename=st, text=text[a:b])); p.addText(text[b:])
                meta["spans"].append({"text": text[a:b], "fmt": "bold" if st is bold else "italic"})
            else:
                p.addText(text)
            doc.text.addElement(p)
        if r.random() < 0.3:
            lst = List()
            for _ in range(r.randint(2, 4)):
                li = ListItem(); li.addElement(P(text=r.choice(SCHOOL))); lst.addElement(li); meta["paragraphs"] += 1
            doc.text.addElement(lst)
        if r.random() < 0.2:
            t = Table(name=f"T{r.randint(1,999)}"); meta["tables"] += 1
            t.addElement(TableColumn(numbercolumnsrepeated=2))
            for _ in range(3):
                tr = TableRow()
                for _ in range(2):
                    c = TableCell(); c.addElement(P(text=r.choice(SCHOOL)[:40])); tr.addElement(c)
                t.addElement(tr)
            doc.text.addElement(t)
    doc.save(path)
    return meta


# ---------------------------------------------------------------- PDF
def gen_pdf(path, seed):
    from reportlab.lib.pagesizes import A4
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image as RLImage
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib import colors
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    r = random.Random(seed)
    fams = {"DejaVu": ("dejavu/DejaVuSans.ttf", "dejavu/DejaVuSans-Bold.ttf"), "LiberationSerif": ("liberation/LiberationSerif-Regular.ttf", "liberation/LiberationSerif-Bold.ttf"),
            "Carlito": ("crosextra/Carlito-Regular.ttf", "crosextra/Carlito-Bold.ttf"), "LiberationSans": ("liberation/LiberationSans-Regular.ttf", "liberation/LiberationSans-Bold.ttf")}
    fam = r.choice(list(fams))
    for i, f in enumerate(fams[fam]):
        name = fam + ("-Bold" if i else "")
        if name not in pdfmetrics.getRegisteredFontNames():
            pdfmetrics.registerFont(TTFont(name, f"{FONTS_DIR}/{f}"))
    from reportlab.lib.fonts import addMapping
    addMapping(fam, 0, 0, fam); addMapping(fam, 1, 0, fam + "-Bold"); addMapping(fam, 0, 1, fam); addMapping(fam, 1, 1, fam + "-Bold")
    ss = getSampleStyleSheet()
    body = ParagraphStyle("b", parent=ss["BodyText"], fontName=fam, fontSize=r.choice([10, 11, 12]), leading=15)
    h1 = ParagraphStyle("h1", parent=ss["Heading1"], fontName=fam + "-Bold", textColor=r.choice([colors.black, colors.HexColor("#1A3D8F"), colors.HexColor("#7A1F1F")]))
    h2 = ParagraphStyle("h2", parent=ss["Heading2"], fontName=fam + "-Bold")
    story = [Paragraph(rnd_title(r), h1)]
    meta = {"kind": "pdf", "seed": seed, "font": fam, "paragraphs": 1, "tables": 0, "images": 0, "spans": []}
    for _ in range(r.randint(3, 14)):
        story.append(Paragraph(r.choice(HEAD_TOPICS).format(n=r.randint(1, 9)), h2)); meta["paragraphs"] += 1
        for _ in range(r.randint(1, 3)):
            text = rnd_par(r).replace("&", "&amp;").replace("<", "&lt;")
            sp = pick_span(r, text) if r.random() < 0.5 else None
            if sp:
                a, b = sp; meta["spans"].append({"text": text[a:b], "fmt": "bold"})
                text = text[:a] + "<b>" + text[a:b] + "</b>" + text[b:]
            story.append(Paragraph(text, body)); meta["paragraphs"] += 1
        if r.random() < 0.2:
            data = [[r.choice(["Notion", "Date", "Formule"]), r.choice(["Explication", "Exemple"])]] + [[r.choice(SCHOOL)[:30], r.choice(SCHOOL)[:40]] for _ in range(r.randint(1, 4))]
            data = [[Paragraph(c, body) for c in row] for row in data]
            t = Table(data, colWidths=[200, 260]); t.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.grey), ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#DCE4F5"))]))
            story.append(t); meta["tables"] += 1
        if r.random() < 0.15:
            story.append(RLImage(make_image(r), width=240, height=150)); meta["images"] += 1
        story.append(Spacer(1, 6))
    SimpleDocTemplate(path, pagesize=A4, title=meta.get("title", "Document"), lang="fr-FR").build(story)
    return meta


# ---------------------------------------------------------------- Images (photos/scans of handouts)
def gen_img(path, seed):
    from PIL import Image, ImageDraw, ImageFont, ImageFilter
    r = random.Random(seed)
    W, H = r.choice([(1240, 1754), (1080, 1440), (1600, 1200), (1920, 1080)])
    style = r.choice(["paper", "paper", "cream", "lined", "slide", "board", "colored"])
    bg = {"paper": (250, 250, 248), "cream": (246, 240, 225), "lined": (252, 252, 250), "slide": (255, 255, 255), "board": (34, 60, 48), "colored": tuple(r.randint(200, 245) for _ in range(3))}[style]
    fg = (240, 240, 235) if style == "board" else r.choice([(20, 20, 20), (30, 30, 80), (60, 20, 20)])
    img = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(img)
    if style == "lined":
        for y in range(120, H, 48): d.line([(0, y), (W, y)], fill=(170, 190, 230), width=2)
        d.line([(110, 0), (110, H)], fill=(230, 150, 150), width=2)
    fonts = ["dejavu/DejaVuSans.ttf", "dejavu/DejaVuSerif.ttf", "liberation/LiberationSans-Regular.ttf", "liberation/LiberationSerif-Regular.ttf", "crosextra/Carlito-Regular.ttf", "freefont/FreeSans.ttf"]
    fontfile = f"{FONTS_DIR}/{r.choice(fonts)}"
    size = r.randint(26, 44) if W < 1500 else r.randint(30, 52)
    font = ImageFont.truetype(fontfile, size); tfont = ImageFont.truetype(fontfile, int(size * 1.5))
    margin = r.randint(60, 140)
    y = margin
    lines_meta = []
    title = rnd_title(r)
    d.text((margin, y), title, font=tfont, fill=fg); bb = d.textbbox((margin, y), title, font=tfont)
    lines_meta.append({"text": title, "bbox": bb, "size": int(size * 1.5)}); y = bb[3] + size
    while y < H - margin - size * 2:
        para = rnd_par(r, short=True)
        words, line = para.split(), ""
        for w in words:
            test = (line + " " + w).strip()
            if d.textlength(test, font=font) > W - 2 * margin:
                d.text((margin, y), line, font=font, fill=fg); bb = d.textbbox((margin, y), line, font=font)
                lines_meta.append({"text": line, "bbox": bb, "size": size}); y += int(size * 1.45); line = w
                if y > H - margin - size: break
            else:
                line = test
        if line and y <= H - margin - size:
            d.text((margin, y), line, font=font, fill=fg); bb = d.textbbox((margin, y), line, font=font)
            lines_meta.append({"text": line, "bbox": bb, "size": size}); y += int(size * 1.45)
        y += int(size * 0.8)
    if r.random() < 0.3: img = img.rotate(r.uniform(-3, 3), expand=False, fillcolor=bg)
    if r.random() < 0.3: img = img.filter(ImageFilter.GaussianBlur(r.uniform(0.3, 1.0)))
    ext = Path(path).suffix.lower()
    img.save(path, quality=r.randint(70, 95)) if ext == ".jpg" else img.save(path)
    return {"kind": "image", "seed": seed, "style": style, "lines": lines_meta, "size": [W, H]}


# ---------------------------------------------------------------- Text formats
def gen_txt(path, seed):
    r = random.Random(seed)
    ext = Path(path).suffix
    if ext == ".srt":
        out, t = [], 0
        for i in range(1, r.randint(10, 40)):
            a, t = t, t + r.randint(1500, 5000)
            f = lambda ms: f"{ms//3600000:02d}:{ms//60000%60:02d}:{ms//1000%60:02d},{ms%1000:03d}"
            out.append(f"{i}\n{f(a)} --> {f(t)}\n{r.choice(SCHOOL)}\n")
        Path(path).write_text("\n".join(out), encoding="utf-8")
        return {"kind": "srt", "seed": seed, "cues": len(out)}
    if ext == ".csv":
        rows = ["Élève;Matière;Note;Appréciation"] + [f"{r.choice(['Léa','Hugo','Inès'])};{r.choice(SUBJECTS)};{r.randint(0,20)};\"{r.choice(SCHOOL)}\"" for _ in range(r.randint(5, 30))]
        Path(path).write_text("\n".join(rows) + "\n", encoding="utf-8")
        return {"kind": "csv", "seed": seed, "rows": len(rows)}
    if ext == ".md":
        lines = [f"# {rnd_title(r)}", ""]
        for _ in range(r.randint(2, 6)):
            lines += [f"## {r.choice(HEAD_TOPICS).format(n=r.randint(1,9))}", "", rnd_par(r), ""]
            lines += [f"- {r.choice(SCHOOL)}" for _ in range(r.randint(0, 4))] + [""]
        Path(path).write_text("\n".join(lines), encoding="utf-8")
        return {"kind": "md", "seed": seed, "lines": len(lines)}
    paras = [rnd_par(r) for _ in range(r.randint(3, 20))]
    Path(path).write_text("\n\n".join(paras) + "\n", encoding="utf-8")
    return {"kind": "txt", "seed": seed, "paragraphs": len(paras)}


GENS = {"docx": gen_docx, "pptx": gen_pptx, "xlsx": gen_xlsx, "odt": gen_odt, "pdf": gen_pdf, "jpg": gen_img, "png": gen_img,
        "txt": gen_txt, "md": gen_txt, "csv": gen_txt, "srt": gen_txt}


def work(args):
    out, ext, i = args
    d = Path(out) / ext; d.mkdir(parents=True, exist_ok=True)
    p = d / f"{ext}_{i:05d}.{ext}"
    if p.exists() and Path(str(p) + ".json").exists():
        return 0
    try:
        meta = GENS[ext](str(p), hash((ext, i)) & 0xFFFFFFF)
        Path(str(p) + ".json").write_text(json.dumps(meta, ensure_ascii=False))
        return 0
    except Exception as e:
        print("FAIL", p, repr(e), file=sys.stderr)
        return 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    for k, v in dict(docx=2000, pptx=1000, xlsx=1000, odt=1000, pdf=2000, jpg=2000, png=1000, txt=500, md=500, csv=500, srt=500).items():
        ap.add_argument(f"--{k}", type=int, default=v)
    a = ap.parse_args()
    jobs = [(a.out, ext, i) for ext in GENS for i in range(getattr(a, ext))]
    random.Random(0).shuffle(jobs)
    with Pool(4) as pool:
        fails = sum(pool.imap_unordered(work, jobs, chunksize=8))
    print("generated", len(jobs), "failed", fails)
