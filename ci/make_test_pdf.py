"""Usage: python3 ci/make_test_pdf.py <out.pdf>

Writes a short story PDF for the emulator test, laid out the way real books are: a title
page, a copyright page and a contents page before the story (which Vaasi should skip to
start at Chapter One), then a running header, page numbers, a word hyphenated across
lines, a paragraph that continues over a page break, and dialogue in quotation marks."""
import sys
from reportlab.lib.pagesizes import A5
from reportlab.pdfgen import canvas

PAGES = [
    [
        ("h", "Chapter One"),
        ("p", ["The lighthouse keeper woke before the sun, as he had done every",
               "morning for thirty years. He climbed the narrow stairs, counted",
               "the steps out of habit, and opened the shutters to the grey sea."]),
        ("p", ["“Another quiet day,” he said to the gulls. They did not answer,",
               "but then they never did."]),
        ("p", ["Far below, a small boat was making its way towards the har-",
               "bour, its sail patched in three colours and its mast leaning"]),
    ],
    [
        ("p", ["a little to one side, as if it were tired of standing up straight."]),
        ("p", ["“Ahoy!” called a voice from the boat. “Is anyone up there?”"]),
        ("p", ["The keeper leaned out of the window. “Only me,” he called back,",
               "“and I have been here long enough to know every wave by name.”"]),
        ("p", ["The stranger laughed and tied the boat to the old iron ring."]),
    ],
    [
        ("h", "Chapter Two"),
        ("p", ["They drank tea in the round kitchen while the wind rattled the",
               "glass. The stranger said she was a map maker, sailing the coast",
               "to draw every cove and rock that the old charts had missed."]),
        ("p", ["“Then you will need a good light,” said the keeper, and smiled."]),
        ("p", ["That night the lamp turned and turned, and the little boat slept",
               "safely in its shadow. The end."]),
    ],
]

FRONT = 3  # title, copyright and contents pages

def front_matter(c, width, height):
    c.setFont("Helvetica-Bold", 22)
    c.drawCentredString(width / 2, height / 2 + 40, "The Lighthouse Keeper")
    c.setFont("Helvetica", 12)
    c.drawCentredString(width / 2, height / 2, "A short story")
    c.drawCentredString(width / 2, height / 2 - 40, "by A. Writer")
    c.showPage()
    c.setFont("Helvetica", 8)
    c.drawString(40, 90, "Copyright © 2026 A. Writer. All rights reserved. No part of this book may be")
    c.drawString(40, 78, "reproduced without permission. First edition.")
    c.showPage()
    c.setFont("Helvetica-Bold", 14)
    c.drawString(40, height - 70, "Contents")
    c.setFont("Helvetica", 10)
    c.drawString(40, height - 100, "Chapter One .................................................. " + str(FRONT + 1))
    c.drawString(40, height - 116, "Chapter Two .................................................. " + str(FRONT + 3))
    c.showPage()

def main(out):
    c = canvas.Canvas(out, pagesize=A5)
    c.setTitle("The Lighthouse Keeper")
    width, height = A5
    front_matter(c, width, height)
    for number, blocks in enumerate(PAGES, start=FRONT + 1):
        c.setFont("Helvetica-Oblique", 8)
        c.drawCentredString(width / 2, height - 30, "The Lighthouse Keeper · A Vaasi test story")
        y = height - 70
        for kind, content in blocks:
            if kind == "h":
                c.setFont("Helvetica-Bold", 14)
                c.drawString(40, y, content)
                y -= 26
            else:
                c.setFont("Helvetica", 10)
                for line in content:
                    c.drawString(40, y, line)
                    y -= 14
                y -= 8
        c.setFont("Helvetica", 9)
        c.drawCentredString(width / 2, 30, str(number))
        c.showPage()
    c.save()

main(sys.argv[1])
