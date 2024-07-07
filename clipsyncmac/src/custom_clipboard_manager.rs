use std::sync::{Arc, RwLock};

use clipboard_rs::{Clipboard, ClipboardContext, ClipboardHandler, ContentFormat};

use crate::multicast_link::MulticastLink;

fn get_clipboard_text_data(ctx: &ClipboardContext) -> Option<String> {
    match ctx.has(ContentFormat::Text) {
        true => match ctx.get_text() {
            Ok(text) => Some(text),
            _ => None,
        },
        false => None,
    }
}

pub struct CustomClipboardManager {
    ctx: ClipboardContext,
    last_pasted: Arc<RwLock<String>>,
    multicast_link: Arc<RwLock<MulticastLink>>,
}

impl CustomClipboardManager {
    pub fn new(
        last_pasted: Arc<RwLock<String>>,
        multicast_link: Arc<RwLock<MulticastLink>>,
    ) -> Self {
        let ctx = ClipboardContext::new().unwrap();

        CustomClipboardManager {
            ctx,
            last_pasted,
            multicast_link,
        }
    }
}

impl ClipboardHandler for CustomClipboardManager {
    fn on_clipboard_change(&mut self) {
        if let Some(txt) = get_clipboard_text_data(&self.ctx) {
            if let Ok(lp) = self.last_pasted.try_read() {
                if *lp == txt {
                    return;
                }

                if let Ok(multicast_link) = self.multicast_link.try_read() {
                    multicast_link.send_data(txt);
                } else {
                    eprintln!("Could not acquire multicast link to send clipboard data");
                }
            }
        }
    }
}
