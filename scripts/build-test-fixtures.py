"""Small generated, copyright-free fixtures for both mobile test suites."""
from pathlib import Path
import struct
import zlib
import zipfile

root = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets'
def png(rgb):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    width, height = 400, 600
    rows = (b'\0' + bytes(rgb) * width) * height
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')

with zipfile.ZipFile(root / 'comic.cbz', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, color in [('page10.png', (230, 80, 70)), ('page2.png', (60, 180, 130)), ('page1.png', (60, 130, 230))]:
        archive.writestr(name, png(color))

objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>']
for page in [1, 2]:
    content = f'BT /F1 28 Tf 60 640 Td (Test PDF page {page}) Tj ET\n'.encode()
    objects += [f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 700] /Resources << /Font << /F1 7 0 R >> >> /Contents {4 if page == 1 else 6} 0 R >>'.encode(), b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n' + content + b'endstream']
objects += [b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
pdf = bytearray(b'%PDF-1.4\n'); offsets = [0]
for index, obj in enumerate(objects, 1):
    offsets.append(len(pdf)); pdf += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
xref = len(pdf)
pdf += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
for offset in offsets[1:]: pdf += f'{offset:010d} 00000 n \n'.encode()
pdf += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
(root / 'sample.pdf').write_bytes(pdf)
