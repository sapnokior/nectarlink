// SPDX-License-Identifier: GPL-3.0-or-later
//! Network change notifications (Wi-Fi switched, cable plugged, VPN up), so
//! paired devices reconnect right away instead of after their backoff.

use std::{
    ffi::c_void,
    sync::{Mutex, OnceLock},
};

use windows::Win32::{
    Foundation::{HANDLE, NO_ERROR},
    NetworkManagement::IpHelper::{
        CancelMibChangeNotify2, MIB_IPINTERFACE_ROW, MIB_NOTIFICATION_TYPE,
        MIB_UNICASTIPADDRESS_ROW, NotifyIpInterfaceChange, NotifyUnicastIpAddressChange,
    },
    Networking::WinSock::AF_UNSPEC,
};

type Callback = Box<dyn Fn() + Send + Sync>;

static CALLBACK: OnceLock<Callback> = OnceLock::new();
static HANDLES: Mutex<Vec<isize>> = Mutex::new(Vec::new());

unsafe extern "system" fn on_change(
    _: *const c_void,
    _: *const MIB_IPINTERFACE_ROW,
    _: MIB_NOTIFICATION_TYPE,
) {
    if let Some(callback) = CALLBACK.get() {
        callback();
    }
}

unsafe extern "system" fn on_addr_change(
    _: *const c_void,
    _: *const MIB_UNICASTIPADDRESS_ROW,
    _: MIB_NOTIFICATION_TYPE,
) {
    if let Some(callback) = CALLBACK.get() {
        callback();
    }
}

/// Calls `callback` (on a system thread) whenever a network interface
/// or IP address changes. Bursts are expected; the callback should debounce.
pub fn watch(callback: impl Fn() + Send + Sync + 'static) {
    if CALLBACK.set(Box::new(callback)).is_err() {
        return; // already watching
    }
    let mut if_handle = HANDLE::default();
    let status = unsafe { NotifyIpInterfaceChange(AF_UNSPEC, Some(on_change), None, false, &mut if_handle) };
    if status == NO_ERROR {
        HANDLES.lock().unwrap_or_else(|e| e.into_inner()).push(if_handle.0 as isize);
    } else {
        tracing::warn!(?status, "can't watch for network interface changes");
    }

    let mut addr_handle = HANDLE::default();
    let status_addr = unsafe { NotifyUnicastIpAddressChange(AF_UNSPEC, Some(on_addr_change), None, false, &mut addr_handle) };
    if status_addr == NO_ERROR {
        HANDLES.lock().unwrap_or_else(|e| e.into_inner()).push(addr_handle.0 as isize);
    } else {
        tracing::warn!(?status_addr, "can't watch for IP address changes");
    }
}

/// Stops the notifications (before exit).
pub fn stop() {
    let handles: Vec<isize> = HANDLES.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect();
    for raw in handles {
        unsafe {
            let _ = CancelMibChangeNotify2(HANDLE(raw as *mut c_void));
        }
    }
}
