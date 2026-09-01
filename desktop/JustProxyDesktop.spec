# -*- mode: python ; coding: utf-8 -*-

from pathlib import Path
import sys

from PyInstaller.utils.hooks import collect_submodules


desktop_dir = Path(SPECPATH)
repository_root = desktop_dir.parent
sdk_source = repository_root / "python" / "src"
sys.path.insert(0, str(sdk_source))

sdk_hidden_imports = collect_submodules("justproxy_client")

analysis = Analysis(
    [str(desktop_dir / "justproxy_desktop.py")],
    pathex=[str(desktop_dir), str(sdk_source)],
    binaries=[],
    datas=[],
    hiddenimports=sdk_hidden_imports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
python_archive = PYZ(analysis.pure)

executable = EXE(
    python_archive,
    analysis.scripts,
    analysis.binaries,
    analysis.datas,
    [],
    name="JustProxyDesktop",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
