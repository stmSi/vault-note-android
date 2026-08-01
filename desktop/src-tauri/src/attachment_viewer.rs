use std::{
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Arc,
};

use uuid::Uuid;

use crate::{
    crypto::AttachmentCrypto,
    error::AppError,
    models::{AttachmentRecord, VaultAttachment},
    storage::{harden_directory, harden_file},
};

const OPEN_DIRECTORY_NAME: &str = "open-attachments";

/// Owns controlled plaintext copies created only for external desktop viewers.
///
/// Each unlocked vault session receives a private directory. Names contain only
/// attachment UUIDs and trusted MIME-derived extensions, never user filenames.
/// Dropping the final clone removes the session, which happens on vault lock or
/// process exit. Stale sessions are removed before a new one is created.
#[derive(Clone)]
pub struct AttachmentViewer {
    inner: Arc<ViewerSession>,
}

struct ViewerSession {
    directory: PathBuf,
}

impl AttachmentViewer {
    pub fn new(app_data_directory: &Path) -> Result<Self, AppError> {
        let root = app_data_directory.join(OPEN_DIRECTORY_NAME);
        recreate_private_directory(&root)?;
        let directory = root.join(Uuid::new_v4().hyphenated().to_string());
        fs::create_dir(&directory)?;
        harden_directory(&directory)?;
        Ok(Self {
            inner: Arc::new(ViewerSession { directory }),
        })
    }

    pub fn open(
        &self,
        record: &AttachmentRecord,
        crypto: &AttachmentCrypto,
    ) -> Result<(), AppError> {
        let path = self.materialize(record, crypto)?;
        if let Err(error) = open_with_default_application(&path) {
            let _ = fs::remove_file(path);
            return Err(error);
        }
        Ok(())
    }

    pub fn discard(&self, attachment: &VaultAttachment) -> Result<(), AppError> {
        let path = self.path_for(attachment)?;
        match fs::remove_file(path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        }
    }

    fn materialize(
        &self,
        record: &AttachmentRecord,
        crypto: &AttachmentCrypto,
    ) -> Result<PathBuf, AppError> {
        let path = self.path_for(&record.attachment)?;
        if path.exists() {
            return Ok(path);
        }
        crypto.export_to(
            &record.encrypted_relative_path,
            &record.attachment.id,
            &path,
        )?;
        if let Err(error) = harden_file(&path) {
            let _ = fs::remove_file(&path);
            return Err(error);
        }
        Ok(path)
    }

    fn path_for(&self, attachment: &VaultAttachment) -> Result<PathBuf, AppError> {
        if !crate::sync_wire::valid_id(&attachment.id) {
            return Err(AppError::InvalidBackup);
        }
        Ok(self.inner.directory.join(format!(
            "{}.{}",
            attachment.id,
            extension_for_attachment(attachment)
        )))
    }
}

impl Drop for ViewerSession {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.directory);
    }
}

fn recreate_private_directory(directory: &Path) -> Result<(), AppError> {
    match fs::symlink_metadata(directory) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            fs::remove_file(directory)?;
        }
        Ok(_) => fs::remove_dir_all(directory)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    fs::create_dir(directory)?;
    harden_directory(directory)
}

fn extension_for_attachment(attachment: &VaultAttachment) -> String {
    let known = match attachment.mime_type.as_str() {
        "text/plain" => Some("txt"),
        "application/pdf" => Some("pdf"),
        "image/png" => Some("png"),
        "image/jpeg" => Some("jpg"),
        "image/gif" => Some("gif"),
        "image/webp" => Some("webp"),
        "application/json" => Some("json"),
        "text/csv" => Some("csv"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => Some("docx"),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => Some("xlsx"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" => Some("pptx"),
        "application/msword" => Some("doc"),
        "application/vnd.ms-excel" => Some("xls"),
        "application/vnd.ms-powerpoint" => Some("ppt"),
        "application/zip" => Some("zip"),
        "audio/mpeg" => Some("mp3"),
        "video/mp4" => Some("mp4"),
        _ => None,
    };
    if let Some(known) = known {
        return known.to_owned();
    }
    let candidate = Path::new(&attachment.display_name)
        .extension()
        .and_then(|extension| extension.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    if (1..=12).contains(&candidate.len())
        && candidate.bytes().all(|byte| byte.is_ascii_alphanumeric())
        && !matches!(
            candidate.as_str(),
            "app"
                | "bat"
                | "cmd"
                | "com"
                | "desktop"
                | "dll"
                | "dylib"
                | "exe"
                | "jar"
                | "js"
                | "lnk"
                | "msi"
                | "ps1"
                | "reg"
                | "scf"
                | "scr"
                | "sh"
                | "so"
                | "url"
                | "vbs"
        )
    {
        candidate
    } else {
        "bin".to_owned()
    }
}

fn open_with_default_application(path: &Path) -> Result<(), AppError> {
    let status = platform_open_command(path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_err(|_| AppError::AttachmentOpenFailed)?;
    if status.success() {
        Ok(())
    } else {
        Err(AppError::AttachmentOpenFailed)
    }
}

#[cfg(target_os = "linux")]
fn platform_open_command(path: &Path) -> Command {
    let mut command = Command::new("xdg-open");
    command.arg(path);
    command
}

#[cfg(target_os = "macos")]
fn platform_open_command(path: &Path) -> Command {
    let mut command = Command::new("open");
    command.arg(path);
    command
}

#[cfg(target_os = "windows")]
fn platform_open_command(path: &Path) -> Command {
    let mut command = Command::new("rundll32.exe");
    command.arg("url.dll,FileProtocolHandler").arg(path);
    command
}

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
fn platform_open_command(_path: &Path) -> Command {
    Command::new("vaultnote-unsupported-platform")
}

#[cfg(test)]
mod tests {
    use std::fs;

    use tempfile::tempdir;

    use super::*;
    use crate::{models::VaultAttachment, vault_key::MasterKey};

    #[test]
    fn session_paths_hide_filenames_and_are_removed_on_drop() {
        let root = tempdir().expect("temporary app data should exist");
        let id = Uuid::new_v4().hyphenated().to_string();
        let crypto = AttachmentCrypto::new(Arc::new(MasterKey::for_tests()), root.path())
            .expect("attachment crypto should initialize");
        let encrypted = crypto
            .encrypt_restored(
                b"private image bytes",
                "private original name.png",
                "image/png",
                &id,
            )
            .expect("fixture should encrypt");
        let record = AttachmentRecord {
            attachment: VaultAttachment {
                id: id.clone(),
                parent_item_id: Uuid::new_v4().hyphenated().to_string(),
                display_name: encrypted.display_name,
                mime_type: encrypted.mime_type,
                file_size: encrypted.plaintext_size,
                sha256: encrypted.sha256,
                created_at_epoch_millis: 1,
            },
            encrypted_relative_path: encrypted.relative_path,
        };
        let viewer = AttachmentViewer::new(root.path()).expect("viewer should initialize");
        let path = viewer
            .materialize(&record, &crypto)
            .expect("viewer copy should decrypt");
        assert_eq!(
            path.file_name().and_then(|name| name.to_str()),
            Some(format!("{id}.png").as_str())
        );
        assert!(!path.to_string_lossy().contains("private original name"));
        assert_eq!(
            fs::read(&path).expect("temporary plaintext should read"),
            b"private image bytes"
        );
        let session = path.parent().expect("session should exist").to_owned();

        let clone = viewer.clone();
        drop(viewer);
        assert!(session.exists());
        drop(clone);
        assert!(!session.exists());
    }

    #[test]
    fn initialization_clears_stale_plaintext_sessions() {
        let root = tempdir().expect("temporary app data should exist");
        let stale = root.path().join(OPEN_DIRECTORY_NAME).join("stale");
        fs::create_dir_all(&stale).expect("stale directory should write");
        fs::write(stale.join("private.pdf"), b"private").expect("stale file should write");

        let viewer = AttachmentViewer::new(root.path()).expect("viewer should initialize");
        assert!(!stale.exists());
        assert!(viewer.inner.directory.exists());
    }

    #[test]
    fn unknown_mime_preserves_safe_extensions_but_blocks_executables() {
        let id = Uuid::new_v4().hyphenated().to_string();
        let mut unknown = VaultAttachment {
            id,
            parent_item_id: Uuid::new_v4().hyphenated().to_string(),
            display_name: "book.epub".to_owned(),
            mime_type: "application/octet-stream".to_owned(),
            file_size: 1,
            sha256: "0".repeat(64),
            created_at_epoch_millis: 1,
        };
        assert_eq!(extension_for_attachment(&unknown), "epub");
        unknown.display_name = "payload.exe".to_owned();
        assert_eq!(extension_for_attachment(&unknown), "bin");
        unknown.display_name = "unsafe.two.parts".to_owned();
        assert_eq!(extension_for_attachment(&unknown), "parts");
    }
}
