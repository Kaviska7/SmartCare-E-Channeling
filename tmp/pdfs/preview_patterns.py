from PIL import Image, ImageOps, ImageDraw
from pathlib import Path
p=Path(__file__).parent
files=sorted(p.glob('pattern-page-*.png'))
for group in range(0,len(files),5):
    canvas=Image.new('RGB',(1500,480),'#cccccc')
    for i,f in enumerate(files[group:group+5]):
        im=Image.open(f).convert('RGB')
        im.thumbnail((290,440))
        canvas.paste(im,(i*300,25))
        ImageDraw.Draw(canvas).text((i*300+10,5),f.stem,fill='black')
    canvas.save(p/f'patterns-contact-{group//5}.png')
