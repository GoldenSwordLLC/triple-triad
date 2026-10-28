import os
from PIL import Image, ImageDraw, ImageFont, ImageEnhance

S = 2
W, H = 360 * S, 740 * S
def f(dp): return int(dp * S)
def font(sz, bold=True):
    return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans" + ("-Bold" if bold else "") + ".ttf", f(sz))
GOLD = (212, 175, 55); GOLD_TEXT = (255, 240, 200); CREAM = (245, 238, 220)
BLUE = (21, 101, 192); RED = (183, 28, 28)
SLOT_FILL = (24, 16, 7); SLOT_OUT = (150, 122, 48)
ART = 'androidApp/src/main/assets/art'
mat = Image.open(ART + '/playmat.jpg').convert('RGB')

def mat_bg():
    s = max(W / mat.width, H / mat.height)
    sw, sh = int(W / s), int(H / s)
    crop = mat.crop(((mat.width - sw)//2, (mat.height - sh)//2, (mat.width - sw)//2 + sw, (mat.height - sh)//2 + sh))
    return ImageEnhance.Brightness(crop.resize((W, H), Image.LANCZOS)).enhance(0.86)

def sprite(no, color, w_dp, rotate=False):
    im = Image.open(ART + '/cards/' + color + '/%03d.jpg' % max(1, min(110, no))).convert('RGB')
    im = im.resize((f(w_dp), f(w_dp * 175 / 125)), Image.LANCZOS)
    if rotate: im = im.rotate(180)
    return im

def score_pill(img, red, blue, rotate=False):
    w, h = f(120), f(25)
    x0 = W - f(16) - w; y0 = f(12)
    box = (x0 - f(3), y0 - f(3), x0 + w + f(3), y0 + h + f(3))
    src = img.crop(box)
    d = ImageDraw.Draw(src)
    d.rounded_rectangle([f(3), f(3), f(3) + w, f(3) + h], radius=f(12), fill=(30, 22, 8))
    d.text((f(3) + f(12), f(3) + h / 2), "RED " + str(red), font=font(13), fill=(255, 120, 100), anchor="lm")
    d.text((f(3) + w - f(12), f(3) + h / 2), str(blue) + " BLUE", font=font(13), fill=(120, 190, 255), anchor="rm")
    if rotate: src = src.rotate(180)
    img.paste(src, box[:2])

def turn_pill(img, text, color, rotate=False):
    w, h = f(128), f(30)
    x0 = (W - w) // 2; y0 = f(150)
    box = (x0 - f(3), y0 - f(3), x0 + w + f(3), y0 + h + f(3))
    src = img.crop(box)
    d = ImageDraw.Draw(src)
    d.rounded_rectangle([f(3), f(3), f(3) + w, f(3) + h], radius=f(18), fill=color)
    d.text((f(3) + w / 2, f(3) + h / 2), text, font=font(17), fill=(255, 255, 255), anchor="mm")
    if rotate: src = src.rotate(180)
    img.paste(src, box[:2])

def chevron(img, side):
    d = ImageDraw.Draw(img)
    x = W - f(16) if side == "right" else f(10)
    d.text((x, f(120)), "›" if side == "right" else "‹", font=font(24), fill=(255, 255, 255), anchor="mm")

CARD_W_DP = 96
CARD_H_DP = 134

def hand_row(img, color, nos, y, rotate=False):
    x = f(14)
    for no in nos:
        img.paste(sprite(no, color, CARD_W_DP, rotate), (int(x), int(y)))
        x += f(96 + 6)

def board_slots(img, placed, flip, highlight_cell):
    bs = f(284); bx = (W - bs) // 2; by = f(246)
    cell = (bs - f(12)) // 3
    card_w = int(cell * 0.68); card_h = int(card_w * 175 / 125)
    dd = ImageDraw.Draw(img)
    for r in range(3):
        for c in range(3):
            x = bx + c * (cell + f(6)); y = by + r * (cell + f(6))
            idx = r * 3 + c
            vidx = (8 - idx) if flip else idx
            if vidx in placed:
                color, no = placed[vidx]
                sp = sprite(no, color, 96, rotate=flip).resize((card_w, card_h), Image.LANCZOS)
                img.paste(sp, (int(x + (cell - card_w) / 2), int(y + (cell - card_h) / 2)))
            else:
                hl = highlight_cell == (r, c)
                dd.rounded_rectangle([x, y, x + cell, y + cell], radius=f(8), fill=SLOT_FILL,
                                     outline=GOLD if hl else SLOT_OUT,
                                     width=f(3) if hl else f(2))

RED_HAND = [1, 4, 14, 52, 7]
BLUE_HAND = [2, 10, 30, 70, 109]
PLACED = {0: ('red', 3), 2: ('blue', 13), 4: ('red', 89), 6: ('blue', 66), 8: ('blue', 109)}

def frame(blue_turn=True, flip=False, menu_open=False, sel_card=None):
    img = mat_bg(); d = ImageDraw.Draw(img)
    d.text((f(16), f(14)), "☰ Menu", font=font(15), fill=(255, 250, 235))
    score_pill(img, 2, 3, rotate=(not blue_turn))
    hand_row(img, 'red', RED_HAND, f(54), rotate=(not blue_turn))
    chevron(img, 'right')
    turn_pill(img, "BLUE'S TURN" if blue_turn else "RED'S TURN", BLUE if blue_turn else RED, rotate=(not blue_turn))
    board_slots(img, PLACED, flip, (1, 1) if sel_card is not None else None)
    hand_row(img, 'blue', BLUE_HAND, H - f(190), rotate=False)
    dd = ImageDraw.Draw(img)
    dd.text((W / 2, H - f(22)), "tap a card, then a highlighted cell", font=font(11), fill=(230, 205, 160), anchor="mm")
    if menu_open:
        scrim = Image.new('L', (W, H), 110)
        img.paste(Image.new('RGB', (W, H), (0, 0, 0)), (0, 0), scrim)
        dd = ImageDraw.Draw(img)
        dd.text((f(16), f(14)), "☰ Menu", font=font(15), fill=(255, 250, 235))
        px0, py0, px1, py1 = f(60), f(250), W - f(60), f(490)
        dd.rounded_rectangle([px0, py0, px1, py1], radius=f(16), fill=(30, 22, 8), outline=GOLD, width=f(2))
        dd.text(((px0 + px1) / 2, py0 + f(26)), "GAME MENU", font=font(15), fill=(230, 205, 160), anchor="mm")
        for i, it in enumerate(["▶  Continue", "↻  Restart", "⚙  Settings", "⇤  Concede & Exit"]):
            yy = py0 + f(70) + i * f(44)
            dd.text((px0 + f(36), yy), it, font=font(14), fill=CREAM)
            if i < 3: dd.line([px0 + f(28), yy + f(32), px1 - f(28), yy + f(32)], fill=(90, 72, 38), width=f(1))
    return img

os.makedirs('design', exist_ok=True)
frame(True, False, False, sel_card=2).save('design/v7-3-game-blue.png')
frame(False, True, False).save('design/v7-4-game-red.png')
frame(True, False, True).save('design/v7-5-ingame-menu.png')
sheet = Image.new('RGB', (360 * 3 + 30 * 4, 740 + 60), (16, 12, 8))
for i, k in enumerate(['v7-3-game-blue', 'v7-4-game-red', 'v7-5-ingame-menu']):
    sheet.paste(Image.open('design/' + k + '.png').resize((360, 740), Image.LANCZOS), (30 + i * 390, 30))
sheet.save('design/v7-game-screens.png')
print("OK")
