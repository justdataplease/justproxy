use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
};

use jni::{
    objects::{JClass, JString},
    sys::{jlong, jstring},
    JNIEnv,
};

use crate::{generate_key_pair, Gateway, GatewayConfig};

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static GATEWAYS: OnceLock<Mutex<HashMap<i64, Gateway>>> = OnceLock::new();
static LAST_ERROR: OnceLock<Mutex<String>> = OnceLock::new();

fn gateways() -> &'static Mutex<HashMap<i64, Gateway>> {
    GATEWAYS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn last_error() -> &'static Mutex<String> {
    LAST_ERROR.get_or_init(|| Mutex::new(String::new()))
}

fn set_last_error(message: impl Into<String>) {
    if let Ok(mut error) = last_error().lock() {
        *error = message.into();
    }
}

fn clear_last_error() {
    set_last_error("");
}

fn new_java_string(env: &JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "native gateway panicked".to_owned()
    }
}

fn stats_json(handle: i64) -> anyhow::Result<String> {
    let registry = gateways()
        .lock()
        .map_err(|_| anyhow::anyhow!("gateway registry lock poisoned"))?;
    let gateway = registry
        .get(&handle)
        .ok_or_else(|| anyhow::anyhow!("unknown gateway handle {handle}"))?;
    Ok(serde_json::to_string(&gateway.snapshot())?)
}

fn stop_gateway(handle: i64) -> anyhow::Result<String> {
    let mut gateway = gateways()
        .lock()
        .map_err(|_| anyhow::anyhow!("gateway registry lock poisoned"))?
        .remove(&handle)
        .ok_or_else(|| anyhow::anyhow!("unknown gateway handle {handle}"))?;
    gateway.stop()?;
    Ok(serde_json::to_string(&gateway.snapshot())?)
}

#[no_mangle]
pub extern "system" fn Java_com_justproxy_app_wireguard_WireGuardNativeGateway_nativeStart(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config_json: JString<'_>,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| -> anyhow::Result<jlong> {
        let json: String = env.get_string(&config_json)?.into();
        let gateway = Gateway::start(GatewayConfig::from_json(&json)?)?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        gateways()
            .lock()
            .map_err(|_| anyhow::anyhow!("gateway registry lock poisoned"))?
            .insert(handle, gateway);
        clear_last_error();
        Ok(handle as jlong)
    })) {
        Ok(Ok(handle)) => handle,
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            0
        }
        Err(payload) => {
            set_last_error(panic_message(payload));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_justproxy_app_wireguard_WireGuardNativeGateway_nativeStop(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| stop_gateway(handle)));
    match result {
        Ok(Ok(json)) => match env.new_string(&json) {
            Ok(value) => {
                clear_last_error();
                value.into_raw()
            }
            Err(error) => {
                set_last_error(format!("cannot return final native stats: {error}"));
                ptr::null_mut()
            }
        },
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            ptr::null_mut()
        }
        Err(payload) => {
            set_last_error(panic_message(payload));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_justproxy_app_wireguard_WireGuardNativeGateway_nativeGetStats(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| stats_json(handle)));
    match result {
        Ok(Ok(json)) => match env.new_string(&json) {
            Ok(value) => {
                clear_last_error();
                value.into_raw()
            }
            Err(error) => {
                set_last_error(format!("cannot return native stats: {error}"));
                ptr::null_mut()
            }
        },
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            ptr::null_mut()
        }
        Err(payload) => {
            set_last_error(panic_message(payload));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_justproxy_app_wireguard_WireGuardNativeGateway_nativeGenerateKeyPair(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    match catch_unwind(|| generate_key_pair().and_then(|pair| Ok(serde_json::to_string(&pair)?))) {
        Ok(Ok(json)) => {
            clear_last_error();
            new_java_string(&env, &json)
        }
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            ptr::null_mut()
        }
        Err(payload) => {
            set_last_error(panic_message(payload));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_justproxy_app_wireguard_WireGuardNativeGateway_nativeGetLastError(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let message = last_error()
        .lock()
        .map(|error| error.clone())
        .unwrap_or_else(|_| "native error lock poisoned".to_owned());
    new_java_string(&env, &message)
}

#[cfg(test)]
mod tests {
    use super::{gateways, stats_json, stop_gateway};
    use crate::{generate_key_pair, Gateway, GatewayConfig, StatsSnapshot};

    #[test]
    fn unknown_stats_handle_is_an_error() {
        let error = stats_json(i64::MAX).unwrap_err();
        assert!(error.to_string().contains("unknown gateway handle"));
    }

    #[test]
    fn unknown_stop_handle_is_an_error() {
        let error = stop_gateway(i64::MIN).unwrap_err();
        assert!(error.to_string().contains("unknown gateway handle"));
    }

    #[test]
    fn stop_returns_a_final_joined_snapshot() {
        let reserved = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = reserved.local_addr().unwrap().port();
        drop(reserved);
        let server = generate_key_pair().unwrap();
        let peer = generate_key_pair().unwrap();
        let config = GatewayConfig::from_json(&format!(
            r#"{{"private_key":"{}","peer_public_key":"{}","listen":"127.0.0.1:{}","network_handle":0,"require_bound_network":false,"peer_ipv4":"10.66.0.2","peer_ipv6":"fd66:6a75:7374::2"}}"#,
            server.private_key, peer.public_key, port
        ))
        .unwrap();
        let gateway = Gateway::start(config).unwrap();
        let handle = -7_777;
        gateways().lock().unwrap().insert(handle, gateway);

        let json = stop_gateway(handle).unwrap();
        let snapshot: StatsSnapshot = serde_json::from_str(&json).unwrap();
        assert!(!snapshot.running);
        assert_eq!(snapshot.active_tcp_flows, 0);
        assert_eq!(snapshot.active_udp_flows, 0);
        assert_eq!(snapshot.fatal_error, None);
        assert!(!json.contains("fatal_error"));
    }
}
