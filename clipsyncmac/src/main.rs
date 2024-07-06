use std::sync::{Arc, RwLock};

use clipboard_rs::{ClipboardWatcher, ClipboardWatcherContext};
use custom_clipboard_manager::CustomClipboardManager;
use multicast_link::{IpType, MulticastLink};

mod custom_clipboard_manager;
mod multicast_link;

fn main() {
    let last_pasted = Arc::new(RwLock::new(String::new()));
    let multicast_link = MulticastLink::new(IpType::IPV4, last_pasted.clone());
    let clipboard_manager = CustomClipboardManager::new(last_pasted, multicast_link);

    let mut watcher = ClipboardWatcherContext::new().unwrap();

    let _shutdown_handler = watcher
        .add_handler(clipboard_manager)
        .get_shutdown_channel();

    watcher.start_watch();
}
