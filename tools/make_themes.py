#!/usr/bin/env python3
"""Темы Cloud Pass / Vice Pass из токенов дизайн-кита набора дизайна (CvTokens.kt, CvViceTokens.kt):
«Классика RPG» (ff7, тёмная), Vice Night, Vice Day → app/src/main/res/values/styles_cloudpass.xml + colors_cloudpass.xml.
Запуск: python3 tools/make_themes.py (из android/)."""
import os
T = {
  'ff7': dict(parent='KeepassDXStyle.Night', primary='#7FD1FF', primaryDark='#101F6E', onPrimary='#0A0F2E',
              primaryContainer='#4266C8', onPrimaryContainer='#F2F4FA', secondary='#F4C552', onSecondary='#141A3A',
              secondaryContainer='#1A2357', onSecondaryContainer='#FFE89A', tertiary='#7CFFCB', onTertiary='#0A0F2E',
              tertiaryContainer='#121A45', onTertiaryContainer='#7CFFCB', error='#FF6B7A', onError='#0A0F2E',
              errorContainer='#5A1A2A', onErrorContainer='#FFD9DE', background='#060A1F', onBackground='#F2F4FA',
              surface='#101F6E', onSurface='#F2F4FA', sLowest='#060A1F', sLow='#0D1440', s='#121A45', sHigh='#1A2357',
              sHighest='#21305F', surfaceVariant='#21253C', onSurfaceVariant='#A9B6DD', outline='#969CB2',
              inverseSurface='#E2E6F1', inverseOnSurface='#141A3A', inversePrimary='#3556B4', textPrimary='#F2F4FA'),
  'vice_night': dict(parent='KeepassDXStyle.Night', primary='#FF2BD6', primaryDark='#07041C', onPrimary='#12062E',
              primaryContainer='#8A2BFF', onPrimaryContainer='#FFF6FD', secondary='#00E5FF', onSecondary='#12062E',
              secondaryContainer='#22105C', onSecondaryContainer='#6FF2FF', tertiary='#FFC83D', onTertiary='#12062E',
              tertiaryContainer='#4A0F7A', onTertiaryContainer='#FFF1A8', error='#FF4D78', onError='#12062E',
              errorContainer='#5C0F2E', onErrorContainer='#FFD6E0', background='#07041C', onBackground='#FFF6FD',
              surface='#160A3D', onSurface='#FFF6FD', sLowest='#05031A', sLow='#0C0628', s='#160A3D', sHigh='#22105C',
              sHighest='#2C1670', surfaceVariant='#22105C', onSurfaceVariant='#E4D2FF', outline='#5B2AA8',
              inverseSurface='#FFF6FD', inverseOnSurface='#12062E', inversePrimary='#D4116F', textPrimary='#FFF6FD'),
  'vice_day': dict(parent='KeepassDXStyle.Light', primary='#D4116F', primaryDark='#0B1F4A', onPrimary='#FFFFFF',
              primaryContainer='#FFD6EA', onPrimaryContainer='#4A0026', secondary='#007C99', onSecondary='#FFFFFF',
              secondaryContainer='#BFF4FF', onSecondaryContainer='#00344A', tertiary='#C24E00', onTertiary='#FFFFFF',
              tertiaryContainer='#FFE0C2', onTertiaryContainer='#4A1C00', error='#C8102E', onError='#FFFFFF',
              errorContainer='#FFDADF', onErrorContainer='#410008', background='#E9F9FF', onBackground='#0B1F4A',
              surface='#FFFFFF', onSurface='#0B1F4A', sLowest='#FFFFFF', sLow='#F4FCFF', s='#E9F9FF', sHigh='#DDF4FC',
              sHighest='#CFEFF9', surfaceVariant='#DDF4FC', onSurfaceVariant='#22406E', outline='#8EDCF2',
              inverseSurface='#0B1F4A', inverseOnSurface='#E9F9FF', inversePrimary='#FF8AC4', textPrimary='#0B1F4A'),
}
STYLE = {'ff7': 'CloudPassStyle.Ff7', 'vice_night': 'CloudPassStyle.ViceNight', 'vice_day': 'CloudPassStyle.ViceDay'}
ITEMS = [('colorPrimary', 'primary'), ('colorPrimaryDark', 'primaryDark'), ('colorOnPrimary', 'onPrimary'),
         ('colorPrimaryContainer', 'primaryContainer'), ('colorOnPrimaryContainer', 'onPrimaryContainer'),
         ('colorSecondary', 'secondary'), ('colorOnSecondary', 'onSecondary'), ('colorSecondaryContainer', 'secondaryContainer'),
         ('colorOnSecondaryContainer', 'onSecondaryContainer'), ('colorTertiary', 'tertiary'), ('colorOnTertiary', 'onTertiary'),
         ('colorTertiaryContainer', 'tertiaryContainer'), ('colorOnTertiaryContainer', 'onTertiaryContainer'),
         ('colorError', 'error'), ('colorErrorContainer', 'errorContainer'), ('colorOnError', 'onError'),
         ('colorOnErrorContainer', 'onErrorContainer'), ('android:windowBackground', 'background'),
         ('android:colorBackground', 'background'), ('colorOnBackground', 'onBackground'), ('colorSurface', 'surface'),
         ('colorOnSurface', 'onSurface'), ('colorSurfaceContainerLowest', 'sLowest'), ('colorSurfaceContainerLow', 'sLow'),
         ('colorSurfaceContainer', 's'), ('colorSurfaceContainerHigh', 'sHigh'), ('colorSurfaceContainerHighest', 'sHighest'),
         ('colorSurfaceVariant', 'surfaceVariant'), ('colorOnSurfaceVariant', 'onSurfaceVariant'), ('colorOutline', 'outline'),
         ('colorOnSurfaceInverse', 'inverseOnSurface'), ('colorSurfaceInverse', 'inverseSurface'),
         ('colorPrimaryInverse', 'inversePrimary'), ('android:textColorHighlight', 'surfaceVariant'),
         ('android:textColorPrimary', 'textPrimary'), ('dialogBackgroundColor', 'background')]
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Создано tools/make_themes.py из токенов дизайн-кита. Правьте скрипт, не файл. -->\n<resources>\n'
colors, styles = [HEAD], [HEAD]
for name, t in T.items():
    for k, v in t.items():
        if k != 'parent':
            colors.append(f'    <color name="cp_{name}_{k}">{v}</color>\n')
    styles.append(f'    <style name="{STYLE[name]}" parent="{t["parent"]}">\n')
    for attr, key in ITEMS:
        styles.append(f'        <item name="{attr}">@color/cp_{name}_{key}</item>\n')
    styles.append('    </style>\n')
colors.append('</resources>\n')
styles.append('</resources>\n')
out = 'app/src/main/res/values'
open(os.path.join(out, 'colors_cloudpass.xml'), 'w').write(''.join(colors))
open(os.path.join(out, 'styles_cloudpass.xml'), 'w').write(''.join(styles))
print('ok', len(T), 'themes')
