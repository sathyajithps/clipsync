// https://github.com/ren40/tauri-plugin-screen-lock-status/blob/master/src/lib.rs

use std::{
    sync::{Arc, RwLock},
    thread,
    time::Duration,
};

use core_foundation::{
    base::{TCFType, ToVoid},
    dictionary::CFDictionary,
    string::CFString,
};

use crate::multicast_link::MulticastLink;

extern "C" {
    fn CGSessionCopyCurrentDictionary() -> core_foundation::dictionary::CFDictionaryRef;
}

pub fn screen_off_on_detector(multicast_link: Arc<RwLock<MulticastLink>>) {
    thread::spawn(move || {
        let mut flg = false;
        loop {
            unsafe {
                let session_dictionary_ref = CGSessionCopyCurrentDictionary();
                let session_dictionary: CFDictionary =
                    CFDictionary::wrap_under_create_rule(session_dictionary_ref);
                let current_session_property;
                match session_dictionary.find(CFString::new("CGSSessionScreenIsLocked").to_void()) {
                    None => current_session_property = false,
                    Some(_) => current_session_property = true,
                };
                if flg != current_session_property {
                    flg = current_session_property;

                    if current_session_property == true {
                        if let Ok(mut multicast_link) = multicast_link.try_write() {
                            multicast_link.dispose();
                            println!("Disposed Multicast Link");
                        }
                    } else {
                        if let Ok(mut multicast_link) = multicast_link.try_write() {
                            multicast_link.create();
                            println!("Created Multicast Link");
                        }
                    }
                }
                thread::sleep(Duration::from_millis(1000));
            }
        }
    });
}
