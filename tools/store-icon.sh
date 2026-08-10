#!/usr/bin/env bash
# Render the launcher icon as the PNG a store listing asks for.
#
# The application ships an adaptive icon: a vector foreground over a solid
# background, both of them resources the build compiles. A store wants one
# square bitmap, so the same two pieces are laid out here as an SVG and
# rasterised — nothing is drawn twice by hand, and a change to the vector is
# one run away from the listing.
#
# The foreground is a VectorDrawable, whose `pathData` is SVG path syntax; the
# translation is the element names and the colour attribute, which is what the
# awk below rewrites.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
vector="${here}/app/src/main/res/drawable/ic_launcher_foreground.xml"
colours="${here}/app/src/main/res/values/colors.xml"
out="${here}/fastlane/metadata/android/en-US/images/icon.png"
size=512

background=$(sed -n 's/.*name="ic_launcher_background">\(#[0-9A-Fa-f]*\)<.*/\1/p' "${colours}")
if [ -z "${background}" ]; then
    echo "no ic_launcher_background in ${colours}" >&2
    exit 1
fi

svg=$(mktemp --suffix=.svg)
trap 'rm -f "${svg}"' EXIT

{
    printf '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">\n'
    printf '  <rect width="108" height="108" fill="%s"/>\n' "${background}"
    # Every <path> of the vector, with its two attributes carried over. The
    # pairs arrive on separate lines in the source, so the colour is held
    # until the path data it belongs to turns up.
    awk '
        /android:fillColor=/ { match($0, /#[0-9A-Fa-f]+/); fill = substr($0, RSTART, RLENGTH) }
        /android:pathData=/ {
            match($0, /pathData="[^"]*"/)
            data = substr($0, RSTART + 10, RLENGTH - 11)
            printf "  <path fill=\"%s\" d=\"%s\"/>\n", fill, data
        }
    ' "${vector}"
    printf '</svg>\n'
} > "${svg}"

mkdir -p "$(dirname "${out}")"
rsvg-convert --width "${size}" --height "${size}" "${svg}" --output "${out}"
echo "wrote ${out} (${size}x${size})"
