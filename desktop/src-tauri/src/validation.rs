use std::collections::HashSet;

use uuid::Uuid;

use crate::{error::AppError, models::VaultSection};

pub const MAX_TITLE_CHARACTERS: usize = 500;
pub const MAX_BODY_CHARACTERS: usize = 100_000;
pub const MAX_QUERY_CHARACTERS: usize = 200;
pub const MAX_RESULTS: usize = 100;
pub const MIN_PASSWORD_CHARACTERS: usize = 12;
pub const MAX_PASSWORD_CHARACTERS: usize = 128;
const MAX_QUERY_TERMS: usize = 8;
const MAX_TERM_CHARACTERS: usize = 64;

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
