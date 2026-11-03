// SPDX-License-Identifier: GPL-3.0-or-later
//! App icons that phones send with their notifications, kept as PNG files
//! (`<data>\cache\icons`) so QML and Windows toasts can show them.

use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};

const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";

static SAVED_HASHES: OnceLock<Mutex<HashMap<String, u64>>> = OnceLock::new();

fn icon_hash(bytes: &[u8]) -> u64 {
    bytes
        .iter()
        .fold(0xcbf2_9ce4_8422_2325_u64, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x0100_0000_01b3))
}

pub fn icons_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("cache").join("icons")
}

/// A file name for a package: package names are already safe
/// (`[A-Za-z0-9._]`); anything else is replaced.
fn file_name(app: &str) -> String {
    let safe: String = app
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '_' { c } else { '_' })
        .collect();
    let safe = safe.trim_matches('.');
    format!("{}.png", if safe.is_empty() { "_" } else { safe })
}

/// Stores an app's icon and returns its path. Only PNG data is accepted.
pub fn save(data_dir: &Path, app: &str, png: &[u8]) -> std::io::Result<PathBuf> {
    if !png.starts_with(PNG_SIGNATURE) {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "not a PNG"));
    }
    let dir = icons_dir(data_dir);
    let path = dir.join(file_name(app));
    let hash = icon_hash(png);

    let already_cached = SAVED_HASHES
        .get_or_init(Default::default)
        .lock()
        .map(|m| m.get(app).copied() == Some(hash))
        .unwrap_or(false);

    if already_cached && path.exists() {
        return Ok(path);
    }

    fs::create_dir_all(&dir)?;
    if fs::read(&path).is_ok_and(|existing| existing == png) {
        if let Ok(mut m) = SAVED_HASHES.get_or_init(Default::default).lock() {
            m.insert(app.to_owned(), hash);
        }
        return Ok(path);
    }
    let tmp = path.with_extension("png.tmp");
    fs::write(&tmp, png)?;
    fs::rename(&tmp, &path)?;
    if let Ok(mut m) = SAVED_HASHES.get_or_init(Default::default).lock() {
        m.insert(app.to_owned(), hash);
    }
    Ok(path)
}

/// A `file:///` URL for QML and toasts.
pub fn file_url(path: &Path) -> String {
    let s = path.to_string_lossy().replace('\\', "/");
    if let Some(unc) = s.strip_prefix("//") {
        format!("file://{}", unc.trim_start_matches('/'))
    } else {
        format!("file:///{}", s.trim_start_matches('/'))
    }
}

/// Returns the cached PNG icon path for `app` if it exists on disk.
pub fn existing(data_dir: &Path, app: &str) -> Option<PathBuf> {
    let path = icons_dir(data_dir).join(file_name(app));
    path.is_file().then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stores_png_icons_under_safe_names() {
        let dir = tempfile::tempdir().unwrap();
        let png = [PNG_SIGNATURE, b"rest"].concat();
        let path = save(dir.path(), "com.whatsapp", &png).unwrap();
        assert_eq!(path.file_name().unwrap(), "com.whatsapp.png");
        assert_eq!(fs::read(&path).unwrap(), png);
        // Saving the same icon again is a no-op; a new one replaces it.
        assert_eq!(save(dir.path(), "com.whatsapp", &png).unwrap(), path);

        assert_eq!(file_name("../../evil\\x"), "_.._evil_x.png");
        assert!(save(dir.path(), "com.bad", b"GIF89a").is_err(), "only PNG");
        assert!(file_url(Path::new(r"C:\a\b.png")).starts_with("file:///C:/a/b.png"));
        assert_eq!(file_url(Path::new(r"\\server\share\a.png")), "file://server/share/a.png");
        assert_eq!(file_url(Path::new("/home/user/a.png")), "file:///home/user/a.png");
    }
}
