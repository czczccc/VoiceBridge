/// One incremental edit: delete [backspaces] UTF-16 code units from the end of the
/// previously applied text, then type [suffix].
///
/// Offsets are counted in UTF-16 code units because that is what a Windows keyboard
/// event consumes; the split always lands on a character boundary, so surrogate pairs
/// are never separated.
#[derive(Debug, PartialEq, Eq)]
pub struct Delta {
    pub backspaces: usize,
    pub suffix: String,
}

/// Longest common prefix of the two texts, expressed as (UTF-16 units, byte offset in `next`).
fn common_prefix(previous: &str, next: &str) -> (usize, usize) {
    let mut units = 0;
    let mut next_byte = 0;
    for (old, new) in previous.chars().zip(next.chars()) {
        if old != new {
            break;
        }
        units += old.len_utf16();
        next_byte += new.len_utf8();
    }
    (units, next_byte)
}

/// Computes the edit that turns `previous` into `next` assuming the caret sits at the
/// end of `previous`. This is the documented MVP constraint: the user does not move the
/// caret and does not select text while a session is active.
pub fn delta(previous: &str, next: &str) -> Delta {
    let (prefix_units, next_byte) = common_prefix(previous, next);
    let previous_units = previous.encode_utf16().count();
    Delta {
        // The prefix can never be longer than the old text, so this cannot underflow.
        backspaces: previous_units.saturating_sub(prefix_units),
        suffix: next[next_byte..].to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appending_only_types_the_new_tail() {
        assert_eq!(
            delta("你好", "你好世界"),
            Delta {
                backspaces: 0,
                suffix: "世界".to_string()
            }
        );
    }

    #[test]
    fn replacing_the_tail_deletes_then_types() {
        assert_eq!(
            delta("我想开发一个工具", "我想开发一个局域网工具"),
            Delta {
                backspaces: 2,
                suffix: "局域网工具".to_string()
            }
        );
    }

    #[test]
    fn deleting_a_tail_needs_no_suffix() {
        assert_eq!(
            delta("你好世界", "你好"),
            Delta {
                backspaces: 2,
                suffix: String::new()
            }
        );
    }

    #[test]
    fn clearing_everything_backspaces_the_whole_text() {
        assert_eq!(
            delta("abc", ""),
            Delta {
                backspaces: 3,
                suffix: String::new()
            }
        );
    }

    #[test]
    fn emoji_are_counted_as_two_code_units_and_never_split() {
        // 😀 is one scalar (4 UTF-8 bytes) but two UTF-16 code units.
        let delta = delta("a😀", "a😀b");
        assert_eq!(
            delta,
            Delta {
                backspaces: 0,
                suffix: "b".to_string()
            }
        );

        let delta = delta("a😀", "a");
        assert_eq!(
            delta,
            Delta {
                backspaces: 2,
                suffix: String::new()
            }
        );

        let delta = delta("😀😀", "😀🙂");
        assert_eq!(
            delta,
            Delta {
                backspaces: 2,
                suffix: "🙂".to_string()
            }
        );
    }

    #[test]
    fn newlines_and_combining_marks_are_handled() {
        assert_eq!(
            delta("第一行", "第一行\n第二行"),
            Delta {
                backspaces: 0,
                suffix: "\n第二行".to_string()
            }
        );
        // e + combining acute stays one grapheme but two scalars.
        assert_eq!(
            delta("e\u{301}", "e"),
            Delta {
                backspaces: 1,
                suffix: String::new()
            }
        );
    }

    #[test]
    fn identical_text_produces_an_empty_edit() {
        assert_eq!(
            delta("same", "same"),
            Delta {
                backspaces: 0,
                suffix: String::new()
            }
        );
    }

    #[test]
    fn unrelated_text_replaces_everything() {
        assert_eq!(
            delta("abc", "xyz"),
            Delta {
                backspaces: 3,
                suffix: "xyz".to_string()
            }
        );
    }
}
