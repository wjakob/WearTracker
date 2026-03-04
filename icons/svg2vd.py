#!/usr/bin/env python3
"""Convert SVG files with CSS styles to Android Vector Drawables."""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# CSS named colors -> hex
CSS_COLORS = {
    'black': '#000000', 'white': '#ffffff', 'red': '#ff0000',
    'green': '#008000', 'blue': '#0000ff', 'yellow': '#ffff00',
    'cyan': '#00ffff', 'magenta': '#ff00ff', 'gray': '#808080',
    'grey': '#808080', 'silver': '#c0c0c0', 'maroon': '#800000',
    'olive': '#808000', 'lime': '#00ff00', 'aqua': '#00ffff',
    'teal': '#008080', 'navy': '#000080', 'fuchsia': '#ff00ff',
    'purple': '#800080', 'orange': '#ffa500',
}


def normalize_color(color):
    """Normalize a CSS color to 6-digit hex."""
    if not color:
        return color
    color = color.strip().lower()
    if color in CSS_COLORS:
        return CSS_COLORS[color]
    if color.startswith('#'):
        hex_part = color[1:]
        if len(hex_part) == 3:
            hex_part = hex_part[0]*2 + hex_part[1]*2 + hex_part[2]*2
            return f'#{hex_part}'
        if len(hex_part) == 4:  # #RGBA
            hex_part = hex_part[0]*2 + hex_part[1]*2 + hex_part[2]*2 + hex_part[3]*2
            return f'#{hex_part}'
    return color

def parse_css(style_text):
    """Parse CSS from <style> element into a dict of class -> properties."""
    classes = {}
    # Remove comments
    style_text = re.sub(r'/\*.*?\*/', '', style_text, flags=re.DOTALL)

    # Match CSS rules: selector { properties }
    for match in re.finditer(r'([^{]+)\{([^}]+)\}', style_text):
        selectors = match.group(1).strip()
        props_text = match.group(2).strip()

        # Parse properties
        props = {}
        for prop in props_text.split(';'):
            prop = prop.strip()
            if ':' in prop:
                key, val = prop.split(':', 1)
                props[key.strip()] = val.strip()

        # Apply to each selector
        for sel in selectors.split(','):
            sel = sel.strip()
            if sel.startswith('.'):
                cls = sel[1:]
                if cls not in classes:
                    classes[cls] = {}
                classes[cls].update(props)

    return classes


def resolve_styles(elem, css_classes):
    """Get resolved fill/stroke/etc for an element."""
    result = {}

    # Get from class
    cls = elem.get('class', '')
    for c in cls.split():
        if c in css_classes:
            result.update(css_classes[c])

    # Inline styles override
    style = elem.get('style', '')
    for prop in style.split(';'):
        prop = prop.strip()
        if ':' in prop:
            key, val = prop.split(':', 1)
            result[key.strip()] = val.strip()

    # Direct attributes override
    for attr in ['fill', 'stroke', 'stroke-width', 'fill-rule', 'opacity']:
        val = elem.get(attr)
        if val is not None:
            result[attr] = val

    return result


def _fmt(val):
    """Format a float, stripping trailing zeros."""
    return f'{val:.6g}'


def shape_to_path(tag, elem):
    """Convert an SVG shape element to a path data string."""
    if tag == 'circle':
        cx = float(elem.get('cx', 0))
        cy = float(elem.get('cy', 0))
        r = float(elem.get('r', 0))
        if r <= 0:
            return ''
        left = _fmt(cx - r)
        right = _fmt(cx + r)
        cy_s = _fmt(cy)
        r_s = _fmt(r)
        return (f'M{left},{cy_s}'
                f'A{r_s},{r_s},0,1,1,{right},{cy_s}'
                f'A{r_s},{r_s},0,1,1,{left},{cy_s}Z')
    elif tag == 'ellipse':
        cx = float(elem.get('cx', 0))
        cy = float(elem.get('cy', 0))
        rx = float(elem.get('rx', 0))
        ry = float(elem.get('ry', 0))
        if rx <= 0 or ry <= 0:
            return ''
        left = _fmt(cx - rx)
        right = _fmt(cx + rx)
        cy_s = _fmt(cy)
        rx_s = _fmt(rx)
        ry_s = _fmt(ry)
        return (f'M{left},{cy_s}'
                f'A{rx_s},{ry_s},0,1,1,{right},{cy_s}'
                f'A{rx_s},{ry_s},0,1,1,{left},{cy_s}Z')
    elif tag == 'rect':
        x = float(elem.get('x', 0))
        y = float(elem.get('y', 0))
        w = float(elem.get('width', 0))
        h = float(elem.get('height', 0))
        if w <= 0 or h <= 0:
            return ''
        return f'M{x},{y}L{x + w},{y}L{x + w},{y + h}L{x},{y + h}Z'
    elif tag == 'line':
        x1 = elem.get('x1', '0')
        y1 = elem.get('y1', '0')
        x2 = elem.get('x2', '0')
        y2 = elem.get('y2', '0')
        return f'M{x1},{y1}L{x2},{y2}'
    elif tag == 'polygon':
        points = elem.get('points', '').strip()
        if not points:
            return ''
        parts = points.replace(',', ' ').split()
        coords = [(parts[i], parts[i+1]) for i in range(0, len(parts) - 1, 2)]
        d = f'M{coords[0][0]},{coords[0][1]}'
        for x, y in coords[1:]:
            d += f'L{x},{y}'
        return d + 'Z'
    elif tag == 'polyline':
        points = elem.get('points', '').strip()
        if not points:
            return ''
        parts = points.replace(',', ' ').split()
        coords = [(parts[i], parts[i+1]) for i in range(0, len(parts) - 1, 2)]
        d = f'M{coords[0][0]},{coords[0][1]}'
        for x, y in coords[1:]:
            d += f'L{x},{y}'
        return d
    return ''


def svg_to_vd(svg_path, output_path, width=24, height=24):
    """Convert an SVG file to an Android Vector Drawable."""
    ET.register_namespace('', 'http://www.w3.org/2000/svg')
    tree = ET.parse(svg_path)
    root = tree.getroot()

    ns = {'svg': 'http://www.w3.org/2000/svg'}

    # Get viewBox
    viewbox = root.get('viewBox', '0 0 64 64')
    parts = viewbox.split()
    vp_width = float(parts[2]) if len(parts) >= 3 else 64
    vp_height = float(parts[3]) if len(parts) >= 4 else 64

    # Parse CSS
    css_classes = {}
    for style_elem in root.iter('{http://www.w3.org/2000/svg}style'):
        if style_elem.text:
            css_classes.update(parse_css(style_elem.text))
    # Also try without namespace
    for style_elem in root.iter('style'):
        if style_elem.text:
            css_classes.update(parse_css(style_elem.text))

    # Collect all paths
    paths = []
    groups_stack = []

    def process_element(elem, parent_opacity=1.0):
        tag = elem.tag
        if '}' in tag:
            tag = tag.split('}')[1]

        if tag == 'g':
            opacity = parent_opacity
            cls = elem.get('class', '')
            for c in cls.split():
                if c in css_classes and 'opacity' in css_classes[c]:
                    opacity *= float(css_classes[c]['opacity'])
            op = elem.get('opacity')
            if op:
                opacity *= float(op)
            for child in elem:
                process_element(child, opacity)
        elif tag == 'path':
            d = elem.get('d', '')
            if not d or not d.strip():
                return
            styles = resolve_styles(elem, css_classes)

            fill = normalize_color(styles.get('fill', '#000000'))
            stroke = normalize_color(styles.get('stroke', ''))
            stroke_width = styles.get('stroke-width', '')
            fill_rule = styles.get('fill-rule', '')
            opacity = parent_opacity
            if 'opacity' in styles:
                opacity *= float(styles['opacity'])

            # Skip if fill is none and no stroke
            if fill == 'none' and not stroke:
                return

            path_info = {'d': d}
            if fill and fill != 'none':
                if opacity < 1.0:
                    # Apply opacity to fill color (ensure 6-digit hex)
                    alpha = int(opacity * 255)
                    hex6 = normalize_color(fill).lstrip('#')
                    if len(hex6) < 6:
                        hex6 = hex6.ljust(6, '0')
                    path_info['fillColor'] = f'#{alpha:02x}{hex6}'
                else:
                    path_info['fillColor'] = fill
            elif fill == 'none':
                path_info['fillColor'] = None

            if stroke and stroke != 'none':
                path_info['strokeColor'] = stroke
                if stroke_width:
                    sw = stroke_width.replace('px', '')
                    path_info['strokeWidth'] = sw

            if fill_rule == 'evenodd':
                path_info['fillType'] = 'evenOdd'

            paths.append(path_info)
        elif tag in ('circle', 'ellipse', 'rect', 'line', 'polygon', 'polyline'):
            d = shape_to_path(tag, elem)
            if not d:
                return
            styles = resolve_styles(elem, css_classes)

            fill = normalize_color(styles.get('fill', '#000000'))
            stroke = normalize_color(styles.get('stroke', ''))
            stroke_width = styles.get('stroke-width', '')
            stroke_linecap = styles.get('stroke-linecap', '')
            stroke_linejoin = styles.get('stroke-linejoin', '')
            fill_rule = styles.get('fill-rule', '')
            opacity = parent_opacity
            if 'opacity' in styles:
                opacity *= float(styles['opacity'])

            if fill == 'none' and not stroke:
                return

            path_info = {'d': d}
            if fill and fill != 'none':
                if opacity < 1.0:
                    alpha = int(opacity * 255)
                    hex6 = normalize_color(fill).lstrip('#')
                    if len(hex6) < 6:
                        hex6 = hex6.ljust(6, '0')
                    path_info['fillColor'] = f'#{alpha:02x}{hex6}'
                else:
                    path_info['fillColor'] = fill
            elif fill == 'none':
                path_info['fillColor'] = None

            if stroke and stroke != 'none':
                path_info['strokeColor'] = stroke
                if stroke_width:
                    sw = stroke_width.replace('px', '')
                    path_info['strokeWidth'] = sw
            if stroke_linecap:
                path_info['strokeLineCap'] = stroke_linecap
            if stroke_linejoin:
                path_info['strokeLineJoin'] = stroke_linejoin

            if fill_rule == 'evenodd':
                path_info['fillType'] = 'evenOdd'

            paths.append(path_info)
        elif tag in ('defs', 'style', 'title', 'desc', 'metadata'):
            pass
        else:
            for child in elem:
                process_element(child, parent_opacity)

    for child in root:
        process_element(child)

    # Generate VD XML
    lines = []
    lines.append(f'<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    lines.append(f'    android:width="{width}dp"')
    lines.append(f'    android:height="{height}dp"')
    lines.append(f'    android:viewportWidth="{vp_width}"')
    lines.append(f'    android:viewportHeight="{vp_height}">')

    for p in paths:
        attrs = []
        if p.get('fillColor'):
            attrs.append(f'android:fillColor="{p["fillColor"]}"')
        elif p.get('fillColor') is None:
            # explicit no fill - only show if stroke
            if p.get('strokeColor'):
                pass  # no fill attr needed
            else:
                continue  # skip invisible paths
        if p.get('strokeColor'):
            attrs.append(f'android:strokeColor="{p["strokeColor"]}"')
        if p.get('strokeWidth'):
            attrs.append(f'android:strokeWidth="{p["strokeWidth"]}"')
        if p.get('strokeLineCap'):
            attrs.append(f'android:strokeLineCap="{p["strokeLineCap"]}"')
        if p.get('strokeLineJoin'):
            attrs.append(f'android:strokeLineJoin="{p["strokeLineJoin"]}"')
        if p.get('fillType'):
            attrs.append(f'android:fillType="{p["fillType"]}"')
        attrs.append(f'android:pathData="{p["d"]}"')

        lines.append(f'    <path')
        for i, attr in enumerate(attrs):
            if i < len(attrs) - 1:
                lines.append(f'        {attr}')
            else:
                lines.append(f'        {attr} />')

    lines.append('</vector>')
    lines.append('')

    output_path.write_text('\n'.join(lines))
    print(f'  {svg_path.name} -> {output_path.name} ({len(paths)} paths)')


if __name__ == '__main__':
    icon_dir = Path(__file__).parent
    out_dir = icon_dir.parent / 'app' / 'src' / 'main' / 'res' / 'drawable'

    conversions = {
        'chain.svg': 'ic_chain.xml',
        'chainring.svg': 'ic_chainring.xml',
        'cassette.svg': 'ic_cassette.xml',
        'derailleur.svg': 'ic_derailleur.xml',
        'brakepad.svg': 'ic_brake.xml',
        'tire.svg': 'ic_tire.xml',
        'frame.svg': 'ic_frame.xml',
    }

    for svg_name, vd_name in conversions.items():
        svg_path = icon_dir / svg_name
        out_path = out_dir / vd_name
        if svg_path.exists():
            svg_to_vd(svg_path, out_path)
        else:
            print(f'  SKIP {svg_name} (not found)')
