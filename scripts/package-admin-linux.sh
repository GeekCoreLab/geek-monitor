#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <version> <output-directory> <appimagetool> <linuxdeploy> <gtk-plugin>" >&2
  exit 2
fi

version="$1"
output_dir="$(realpath -m "$2")"
appimagetool="$(realpath "$3")"
linuxdeploy="$(realpath "$4")"
gtk_plugin="$(realpath "$5")"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bundle="$repo_root/admin-flutter/build/linux/x64/release/bundle"
icon="$repo_root/admin-flutter/web/icons/Icon-512.png"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

if [[ ! -x "$bundle/geek_monitor_admin" || ! -f "$icon" ]]; then
  echo "Flutter Linux release bundle or app icon is missing" >&2
  exit 1
fi

mkdir -p "$output_dir"
desktop_file="$work_dir/geek-monitor-admin.desktop"
cat >"$desktop_file" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Geek Monitor
Comment=Self-hosted service monitoring console
Exec=geek-monitor-admin
Icon=geek-monitor-admin
Terminal=false
Categories=System;Network;
DESKTOP
desktop-file-validate "$desktop_file"

deb_root="$work_dir/deb-root"
install -d "$deb_root/opt/geek-monitor-admin" \
  "$deb_root/usr/bin" \
  "$deb_root/usr/share/applications" \
  "$deb_root/usr/share/icons/hicolor/512x512/apps" \
  "$deb_root/DEBIAN"
cp -a "$bundle/." "$deb_root/opt/geek-monitor-admin/"
ln -s /opt/geek-monitor-admin/geek_monitor_admin "$deb_root/usr/bin/geek-monitor-admin"
install -m 0644 "$desktop_file" \
  "$deb_root/usr/share/applications/geek-monitor-admin.desktop"
install -m 0644 "$icon" \
  "$deb_root/usr/share/icons/hicolor/512x512/apps/geek-monitor-admin.png"
cat >"$deb_root/DEBIAN/control" <<CONTROL
Package: geek-monitor-admin
Version: $version
Section: utils
Priority: optional
Architecture: amd64
Maintainer: GeekCoreLab
Depends: libc6, libgtk-3-0 | libgtk-3-0t64, libstdc++6, libgcc-s1, liblzma5, libblkid1
Description: Geek Monitor desktop administration console
CONTROL
dpkg-deb --root-owner-group --build "$deb_root" \
  "$output_dir/geek-monitor-admin_${version}_amd64.deb" >/dev/null

rpm_base_version="${version%%-*}"
rpm_release="1"
if [[ "$version" == *-* ]]; then
  rpm_release="${version#*-}"
  rpm_release="${rpm_release//[^A-Za-z0-9_]/.}"
fi
rpm_top="$work_dir/rpmbuild"
mkdir -p "$rpm_top/BUILD" "$rpm_top/BUILDROOT" "$rpm_top/RPMS" \
  "$rpm_top/SOURCES" "$rpm_top/SPECS" "$rpm_top/SRPMS"
cat >"$rpm_top/SPECS/geek-monitor-admin.spec" <<'SPEC'
Name: geek-monitor-admin
Version: %{app_version}
Release: %{app_release}%{?dist}
Summary: Geek Monitor desktop administration console
License: Proprietary
BuildArch: x86_64
Requires: gtk3, glibc, libstdc++, libgcc, xz-libs, libblkid

%description
Desktop administration console for Geek Monitor.

%prep

%build

%install
install -d "%{buildroot}/opt/geek-monitor-admin" \
  "%{buildroot}/usr/bin" \
  "%{buildroot}/usr/share/applications" \
  "%{buildroot}/usr/share/icons/hicolor/512x512/apps"
cp -a "%{app_bundle}/." "%{buildroot}/opt/geek-monitor-admin/"
ln -s /opt/geek-monitor-admin/geek_monitor_admin \
  "%{buildroot}/usr/bin/geek-monitor-admin"
install -m 0644 "%{app_desktop}" \
  "%{buildroot}/usr/share/applications/geek-monitor-admin.desktop"
install -m 0644 "%{app_icon}" \
  "%{buildroot}/usr/share/icons/hicolor/512x512/apps/geek-monitor-admin.png"

%files
/opt/geek-monitor-admin
/usr/bin/geek-monitor-admin
/usr/share/applications/geek-monitor-admin.desktop
/usr/share/icons/hicolor/512x512/apps/geek-monitor-admin.png
SPEC
rpmbuild --define "_topdir $rpm_top" \
  --define "app_version $rpm_base_version" \
  --define "app_release $rpm_release" \
  --define "app_bundle $bundle" \
  --define "app_desktop $desktop_file" \
  --define "app_icon $icon" \
  -bb "$rpm_top/SPECS/geek-monitor-admin.spec" >/dev/null
cp "$rpm_top"/RPMS/x86_64/*.rpm "$output_dir/"

appdir="$work_dir/GeekMonitor.AppDir"
appimage_desktop="$work_dir/geek-monitor-admin-appimage.desktop"
sed 's/^Exec=.*/Exec=AppRun/' "$desktop_file" >"$appimage_desktop"
desktop-file-validate "$appimage_desktop"
install -d "$appdir/usr/bin" \
  "$appdir/usr/share/applications" \
  "$appdir/usr/share/icons/hicolor/512x512/apps"
cp -a "$bundle/." "$appdir/usr/bin/"
install -m 0644 "$appimage_desktop" "$appdir/geek-monitor-admin.desktop"
install -m 0644 "$icon" "$appdir/geek-monitor-admin.png"
install -m 0644 "$appimage_desktop" \
  "$appdir/usr/share/applications/geek-monitor-admin.desktop"
install -m 0644 "$icon" \
  "$appdir/usr/share/icons/hicolor/512x512/apps/geek-monitor-admin.png"
ln -s geek-monitor-admin.png "$appdir/.DirIcon"
PATH="$(dirname "$gtk_plugin"):$(dirname "$linuxdeploy"):$PATH" \
  "$linuxdeploy" --appimage-extract-and-run \
  --appdir "$appdir" \
  --executable "$bundle/geek_monitor_admin" \
  --desktop-file "$appdir/geek-monitor-admin.desktop" \
  --icon-file "$appdir/geek-monitor-admin.png" \
  --plugin gtk
cat >"$appdir/AppRun" <<'APPRUN'
#!/bin/sh
HERE="${APPDIR:-$(dirname "$(readlink -f "$0")")}"
export APPDIR="$HERE"
for hook in "$HERE"/apprun-hooks/*.sh; do
  [ -f "$hook" ] && . "$hook"
done
export LD_LIBRARY_PATH="$HERE/usr/lib:$HERE/usr/lib/x86_64-linux-gnu:$HERE/usr/bin/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$HERE/usr/bin/geek_monitor_admin" "$@"
APPRUN
chmod 0755 "$appdir/AppRun"
ARCH=x86_64 "$appimagetool" --appimage-extract-and-run "$appdir" \
  "$output_dir/geek-monitor-admin-${version}-x86_64.AppImage"

(
  cd "$output_dir"
  sha256sum ./*.deb ./*.rpm ./*.AppImage >SHA256SUMS
  sha256sum --check SHA256SUMS
)
