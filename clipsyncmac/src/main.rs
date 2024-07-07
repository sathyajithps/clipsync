use std::sync::{Arc, RwLock};

use clipboard_rs::{ClipboardWatcher, ClipboardWatcherContext};
use custom_clipboard_manager::CustomClipboardManager;
use multicast_link::{IpType, MulticastLink};
use screen_off_on_detector::screen_off_on_detector;

mod custom_clipboard_manager;
mod multicast_link;
mod screen_off_on_detector;

fn main() {
    let last_pasted = Arc::new(RwLock::new(String::new()));
    let multicast_link = MulticastLink::new(IpType::IPV4, last_pasted.clone());
    let multicast_link = Arc::new(RwLock::new(multicast_link));
    let clipboard_manager = CustomClipboardManager::new(last_pasted, multicast_link.clone());

    screen_off_on_detector(multicast_link);

    let mut watcher = ClipboardWatcherContext::new().unwrap();

    let _shutdown_handler = watcher
        .add_handler(clipboard_manager)
        .get_shutdown_channel();

    watcher.start_watch();
}
