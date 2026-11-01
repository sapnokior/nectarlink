// SPDX-License-Identifier: GPL-3.0-or-later
//! Windows UserNotificationListener integration: captures Windows toast
//! notifications and forwards them to connected phones when enabled.

use std::{
    collections::HashSet,
    sync::mpsc,
    time::Duration,
};

use windows::{
    Foundation::TypedEventHandler,
    UI::Notifications::{
        KnownNotificationBindings, NotificationKinds, UserNotification,
        Management::{UserNotificationListener, UserNotificationListenerAccessStatus},
    },
    Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx},
};

use crate::{pc_notifications, settings};

/// Starts the Windows UserNotificationListener background thread.
pub fn start() {
    std::thread::Builder::new()
        .name("nectarlink-pc-notifications".into())
        .spawn(move || {
            if let Err(e) = run_listener() {
                tracing::warn!(error = %e, "PC notification listener terminated");
            }
        })
        .expect("failed to spawn pc-notifications thread");
}

fn process_notification(notif: &UserNotification) {
    let Ok(app_info) = notif.AppInfo() else { return };
    let app_name = app_info
        .DisplayInfo()
        .and_then(|d| d.DisplayName())
        .map(|h| h.to_string())
        .unwrap_or_else(|_| "Windows".to_string());

    let aumid = app_info.AppUserModelId().map(|h| h.to_string()).unwrap_or_default();
    if aumid == "Nectarlink.Desktop" || app_name.eq_ignore_ascii_case("Nectarlink") {
        return;
    }

    let Ok(toast_wrapper) = notif.Notification() else { return };
    let Ok(visual) = toast_wrapper.Visual() else { return };

    let binding = visual
        .GetBinding(&KnownNotificationBindings::ToastGeneric().unwrap_or_default())
        .ok()
        .or_else(|| visual.Bindings().ok().and_then(|b| b.GetAt(0).ok()));

    let Some(binding) = binding else { return };
    let Ok(text_elements) = binding.GetTextElements() else { return };
    let size = text_elements.Size().unwrap_or(0);
    if size == 0 {
        return;
    }

    let title = text_elements
        .GetAt(0)
        .and_then(|t| t.Text())
        .map(|h| h.to_string())
        .unwrap_or_default();

    let mut body = String::new();
    for i in 1..size {
        if let Ok(elem) = text_elements.GetAt(i) {
            if let Ok(text) = elem.Text() {
                let s = text.to_string();
                if !s.is_empty() {
                    if !body.is_empty() {
                        body.push('\n');
                    }
                    body.push_str(&s);
                }
            }
        }
    }

    if title.trim().is_empty() && body.trim().is_empty() {
        return;
    }

    tracing::info!(app = %app_name, title = %title, "forwarding PC notification to phone");
    pc_notifications::forward_to_phones(&app_name, &title, &body);
}

fn run_listener() -> windows::core::Result<()> {
    // SAFETY: COM initialization for this background listener thread.
    unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.ok()?;

    let listener = UserNotificationListener::Current()?;
    let status = listener.GetAccessStatus()?;
    let allowed = if status == UserNotificationListenerAccessStatus::Allowed {
        true
    } else if status == UserNotificationListenerAccessStatus::Unspecified {
        match listener.RequestAccessAsync().and_then(|op| op.join()) {
            Ok(s) => s == UserNotificationListenerAccessStatus::Allowed,
            Err(e) => {
                tracing::warn!(error = %e, "RequestAccessAsync failed");
                false
            }
        }
    } else {
        false
    };

    if !allowed {
        tracing::warn!("Windows UserNotificationListener access was denied or not allowed");
        return Ok(());
    }

    let mut seen_ids: HashSet<u32> = HashSet::new();
    if let Ok(existing) = listener
        .GetNotificationsAsync(NotificationKinds::Toast)
        .and_then(|op| op.join())
    {
        if let Ok(size) = existing.Size() {
            for i in 0..size {
                if let Ok(n) = existing.GetAt(i) {
                    if let Ok(id) = n.Id() {
                        seen_ids.insert(id);
                    }
                }
            }
        }
    }

    let (tx, rx) = mpsc::channel::<()>();
    let tx_event = tx.clone();
    let _token = listener.NotificationChanged(&TypedEventHandler::new(move |_, _| {
        let _ = tx_event.send(());
        Ok(())
    }));

    loop {
        let _ = rx.recv_timeout(Duration::from_millis(1500));

        if !settings::forward_pc_notifications_to_phone() {
            continue;
        }

        let notifications = match listener
            .GetNotificationsAsync(NotificationKinds::Toast)
            .and_then(|op| op.join())
        {
            Ok(n) => n,
            Err(e) => {
                tracing::debug!(error = %e, "failed to get current notifications");
                continue;
            }
        };

        let size = match notifications.Size() {
            Ok(s) => s,
            Err(_) => continue,
        };

        let mut current_ids = HashSet::new();
        for i in 0..size {
            let Ok(notif) = notifications.GetAt(i) else { continue };
            let Ok(id) = notif.Id() else { continue };
            current_ids.insert(id);

            if !seen_ids.contains(&id) {
                seen_ids.insert(id);
                process_notification(&notif);
            }
        }

        seen_ids.retain(|id| current_ids.contains(id));
    }
}
