"""The e2e's sound and document, written byte by byte: the box has no ffmpeg.

    python3 -I test/e2e/fixtures/make-fixtures.py test/e2e/fixtures

e2e-chime.mp3: forty MPEG-1 Layer III frames (128 kbit/s, 44.1 kHz, mono)
whose side information is all zero, which decodes as a second of silence.
e2e-letter.pdf: one page, one line of Helvetica, xref offsets computed.
The mp4 comes from record-mp4.js.
"""
import sys, os
out = sys.argv[1]
# mp3: MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, mono. Zero side info = silence.
hdr = bytes([0xFF, 0xFB, 0x90, 0xC0])
frame = hdr + bytes(417 - 4)
open(os.path.join(out, "e2e-chime.mp3"), "wb").write(frame * 40)
# pdf: one page with one line of text, xref offsets computed.
objs = [
 b"<< /Type /Catalog /Pages 2 0 R >>",
 b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
 b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
 None,
 b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
]
stream = b"BT /F1 18 Tf 40 100 Td (Rhizome e2e letter) Tj ET"
objs[3] = b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream"
pdf = b"%PDF-1.4\n"
offs = []
for i, o in enumerate(objs, 1):
    offs.append(len(pdf)); pdf += b"%d 0 obj\n" % i + o + b"\nendobj\n"
x = len(pdf)
pdf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs)+1)
for o in offs: pdf += b"%010d 00000 n \n" % o
pdf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs)+1, x)
open(os.path.join(out, "e2e-letter.pdf"), "wb").write(pdf)
