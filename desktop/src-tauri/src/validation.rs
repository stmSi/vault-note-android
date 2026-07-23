use std::collections::HashSet;

use uuid::Uuid;

use crate::{
    error::AppError,
    models::{DatedEntryDraft, NoteBodyDocument, VaultSection},
};

pub const MAX_TITLE_CHARACTERS: usize = 500;
pub const MAX_BODY_CHARACTERS: usize = 100_000;
pub const MAX_QUERY_CHARACTERS: usize = 200;
pub const MAX_RESULTS: usize = 100;
pub const MIN_PASSWORD_CHARACTERS: usize = 12;
pub const MAX_PASSWORD_CHARACTERS: usize = 128;
const MAX_QUERY_TERMS: usize = 8;
const MAX_TERM_CHARACTERS: usize = 64;
const MAX_BODY_BLOCKS: usize = 10_000;
const MAX_DATE_LABEL_CHARACTERS: usize = 500;
const MAX_ALERTS_PER_ENTRY: usize = 5;
const MAX_ALERT_LEAD_MINUTES: i64 = 5_256_000;

pub fn validate_id(id: &str) -> Result<(), AppError> {
    let parsed = Uuid::parse_str(id).map_err(|_| AppError::InvalidInput {
        field: "id",
        reason: "invalid UUID".to_owned(),
    })?;
    if parsed.hyphenated().to_string() != id.to_ascii_lowercase() {
        return Err(AppError::InvalidInput {
            field: "id",
            reason: "UUID must use canonical hyphenated form".to_owned(),
        });
    }
    Ok(())
}

pub fn validate_note(title: &str, body: &str) -> Result<(), AppError> {
    validate_text("title", title, MAX_TITLE_CHARACTERS)?;
    validate_text("body", body, MAX_BODY_CHARACTERS)
}

pub fn validate_note_document(
    title: &str,
    document: &NoteBodyDocument,
) -> Result<String, AppError> {
    validate_text("title", title, MAX_TITLE_CHARACTERS)?;
    if document.version != 1 || document.blocks.len() > MAX_BODY_BLOCKS {
        return Err(invalid("body", "unsupported block document"));
    }
    let mut ids = HashSet::with_capacity(document.blocks.len());
    let mut lines = Vec::with_capacity(document.blocks.len());
    let mut body_characters = 0_usize;
    for block in &document.blocks {
        validate_id(&block.id).map_err(|_| invalid("body", "invalid block identifier"))?;
        if !ids.insert(block.id.as_str()) || block.text.contains('\0') {
            return Err(invalid("body", "invalid block"));
        }
        body_characters = body_characters
            .checked_add(block.text.chars().count())
            .and_then(|count| count.checked_add(1))
            .ok_or_else(|| invalid("body", "too large"))?;
        let line = match block.block_type.as_str() {
            "PARAGRAPH" if !block.checked => block.text.clone(),
            "CHECKLIST_ITEM" => {
                format!(
                    "{} {}",
                    if block.checked { "[x]" } else { "[ ]" },
                    block.text
                )
            }
            _ => return Err(invalid("body", "unknown block type")),
        };
        lines.push(line);
    }
    let body = lines.join("\n");
    validate_text("body", &body, MAX_BODY_CHARACTERS)?;
    Ok(body)
}

pub fn validate_dated_entry(draft: &DatedEntryDraft) -> Result<(), AppError> {
    if let Some(id) = &draft.id {
        validate_id(id)?;
    }
    if !matches!(
        draft.entry_type.as_str(),
        "REMINDER" | "DEADLINE" | "IMPORTANT_DATE" | "RENEWAL"
    ) {
        return Err(invalid("date", "unknown date type"));
    }
    validate_text("date", &draft.label, MAX_DATE_LABEL_CHARACTERS)?;
    if draft.occurrence_at_epoch_millis < 0
        || chrono::DateTime::<chrono::Utc>::from_timestamp_millis(draft.occurrence_at_epoch_millis)
            .is_none()
    {
        return Err(invalid("date", "invalid occurrence"));
    }
    if draft.time_zone_id.len() > 100 || draft.time_zone_id.parse::<chrono_tz::Tz>().is_err() {
        return Err(invalid("date", "invalid time zone"));
    }
    match (&draft.recurrence_unit, draft.recurrence_interval) {
        (None, None) => {}
        (Some(unit), Some(interval))
            if matches!(unit.as_str(), "DAY" | "WEEK" | "MONTH" | "YEAR")
                && (1..=999).contains(&interval) => {}
        _ => return Err(invalid("date", "invalid recurrence")),
    }
    let mut alerts = HashSet::new();
    if draft.alert_lead_times_minutes.len() > MAX_ALERTS_PER_ENTRY
        || draft
            .alert_lead_times_minutes
            .iter()
            .any(|lead| !alerts.insert(*lead) || !(0..=MAX_ALERT_LEAD_MINUTES).contains(lead))
    {
        return Err(invalid("date", "invalid alert"));
    }
    Ok(())
}

pub fn validate_password(password: &str) -> Result<(), AppError> {
    let count = password.chars().count();
    if !(MIN_PASSWORD_CHARACTERS..=MAX_PASSWORD_CHARACTERS).contains(&count)
        || password.contains('\0')
    {
        return Err(AppError::InvalidInput {
            field: "password",
            reason: format!(
                "must contain {MIN_PASSWORD_CHARACTERS} to {MAX_PASSWORD_CHARACTERS} characters"
            ),
        });
    }
    Ok(())
}

fn validate_text(field: &'static str, value: &str, maximum: usize) -> Result<(), AppError> {
    if value.chars().count() > maximum {
        return Err(AppError::InvalidInput {
            field,
            reason: format!("must contain at most {maximum} characters"),
        });
    }
    if value.contains('\0') {
        return Err(AppError::InvalidInput {
            field,
            reason: "must not contain NUL characters".to_owned(),
        });
    }
    Ok(())
}

fn invalid(field: &'static str, reason: &str) -> AppError {
    AppError::InvalidInput {
        field,
        reason: reason.to_owned(),
    }
}

pub fn parse_section(value: &str) -> Result<VaultSection, AppError> {
    match value {
        "active" => Ok(VaultSection::Active),
        "archived" => Ok(VaultSection::Archived),
        "trash" => Ok(VaultSection::Trash),
        _ => Err(AppError::InvalidInput {
            field: "section",
            reason: "unknown vault section".to_owned(),
        }),
    }
}

pub fn validate_limit(limit: usize) -> Result<usize, AppError> {
    if (1..=MAX_RESULTS).contains(&limit) {
        Ok(limit)
    } else {
        Err(AppError::InvalidInput {
            field: "limit",
            reason: format!("must be between 1 and {MAX_RESULTS}"),
        })
    }
}

pub fn compile_search_query(input: &str) -> Result<Option<String>, AppError> {
    if input.chars().count() > MAX_QUERY_CHARACTERS {
        return Err(AppError::InvalidInput {
            field: "query",
            reason: format!("must contain at most {MAX_QUERY_CHARACTERS} characters"),
        });
    }

    let mut terms = Vec::new();
    let mut seen = HashSet::new();
    let mut current = String::new();

    let push_term = |current: &mut String, terms: &mut Vec<String>, seen: &mut HashSet<String>| {
        if current.is_empty() || terms.len() >= MAX_QUERY_TERMS {
            current.clear();
            return;
        }
        let normalized = current.to_lowercase();
        if seen.insert(normalized) {
            terms.push(std::mem::take(current));
        } else {
            current.clear();
        }
    };

    for character in input.chars() {
        if character.is_alphanumeric() {
            if current.chars().count() < MAX_TERM_CHARACTERS {
                current.push(character);
            }
        } else {
            push_term(&mut current, &mut terms, &mut seen);
        }
        if terms.len() >= MAX_QUERY_TERMS {
            break;
        }
    }
    push_term(&mut current, &mut terms, &mut seen);

    if terms.is_empty() {
        return Ok(None);
    }

    let expression = terms
        .into_iter()
        .map(|term| format!("\"{term}\"*"))
        .collect::<Vec<_>>()
        .join(" AND ");
    Ok(Some(expression))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn search_operators_are_reduced_to_safe_prefix_terms() {
        let compiled = compile_search_query("paper OR secret* -tag")
            .expect("query should validate")
            .expect("query should contain terms");
        assert_eq!(
            compiled,
            "\"paper\"* AND \"OR\"* AND \"secret\"* AND \"tag\"*"
        );
    }

    #[test]
    fn punctuation_only_search_is_empty() {
        assert_eq!(
            compile_search_query("\"*()-").expect("query should validate"),
            None
        );
    }
}
