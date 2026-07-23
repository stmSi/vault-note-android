use std::{fs, path::PathBuf};

use tauri::{AppHandle, Manager};

use crate::error::AppError;

pub fn prepare_database_path(app: &AppHandle) -> Result<PathBuf, AppError> {
    let directory = app
        .path()
        .app_data_dir()
        .map_err(|_| AppError::Storage(std::io::Error::other("app data directory unavailable")))?;
    fs::create_dir_all(&directory)?;
    harden_directory(&directory)?;
    Ok(directory.join("vaultnote.db"))
}

pub fn harden_database_file(path: &std::path::Path) -> Result<(), AppError> {
    for suffix in ["", "-wal", "-shm"] {
        let mut sidecar = path.as_os_str().to_os_string();
        sidecar.push(suffix);
        let sidecar = std::path::PathBuf::from(sidecar);
        if sidecar.exists() {
            harden_file(&sidecar)?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn harden_directory(path: &std::path::Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_directory(_path: &std::path::Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(unix)]
fn harden_file(path: &std::path::Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_file(_path: &std::path::Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(all(test, unix))]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use tempfile::tempdir;

    use super::*;

    #[test]
    fn database_and_sidecars_are_private() {
        let directory = tempdir().expect("temporary directory should exist");
        let database = directory.path().join("vaultnote.db");
        for suffix in ["", "-wal", "-shm"] {
            let mut path = database.as_os_str().to_os_string();
            path.push(suffix);
            fs::write(std::path::PathBuf::from(path), []).expect("file should be created");
        }
        harden_database_file(&database).expect("database files should be hardened");

        for suffix in ["", "-wal", "-shm"] {
            let mut path = database.as_os_str().to_os_string();
            path.push(suffix);
            let mode = fs::metadata(std::path::PathBuf::from(path))
                .expect("file should exist")
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(mode, 0o600);
        }
    }
}
