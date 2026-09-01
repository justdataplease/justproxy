use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub(crate) fn source_is_assigned(
    source: IpAddr,
    assigned_v4: Option<Ipv4Addr>,
    assigned_v6: Option<Ipv6Addr>,
) -> bool {
    match source {
        IpAddr::V4(address) => assigned_v4 == Some(address),
        IpAddr::V6(address) => assigned_v6 == Some(address),
    }
}

pub(crate) fn destination_is_allowed(address: IpAddr, port: u16) -> bool {
    if port == 0 || port == 25 {
        return false;
    }
    match address {
        IpAddr::V4(address) => ipv4_is_public(address),
        IpAddr::V6(address) => ipv6_is_public(address),
    }
}

fn ipv4_is_public(address: Ipv4Addr) -> bool {
    let octets = address.octets();
    let [a, b, c, d] = octets;
    if a == 0
        || a == 10
        || a == 127
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 198 && (b == 18 || b == 19))
        || a >= 224
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 192 && b == 88 && c == 99)
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113)
    {
        return false;
    }
    !(a == 255 && b == 255 && c == 255 && d == 255)
}

fn ipv6_is_public(address: Ipv6Addr) -> bool {
    if let Some(mapped) = address.to_ipv4_mapped() {
        return ipv4_is_public(mapped);
    }
    let octets = address.octets();

    // The well-known NAT64 prefix is globally routed through the selected
    // Android network even though it is outside 2000::/3.
    if octets[..12] == [0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0] {
        return true;
    }

    // Global unicast. Exclude documentation, benchmarking, and ORCHID ranges.
    if (octets[0] & 0xe0) == 0x20 {
        if octets[..4] == [0x20, 0x01, 0x0d, 0xb8]
            || octets[..6] == [0x20, 0x01, 0x00, 0x02, 0, 0]
            || (octets[0] == 0x20 && octets[1] == 0x01 && (octets[2] & 0xf0) == 0x10)
            || (octets[0] == 0x20 && octets[1] == 0x01 && (octets[2] & 0xf0) == 0x20)
        {
            return false;
        }
        return true;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::{destination_is_allowed, source_is_assigned};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    #[test]
    fn only_the_assigned_inner_address_is_accepted() {
        let assigned = Ipv4Addr::new(10, 66, 0, 2);
        assert!(source_is_assigned(
            IpAddr::V4(assigned),
            Some(assigned),
            None
        ));
        assert!(!source_is_assigned(
            IpAddr::V4(Ipv4Addr::new(10, 66, 0, 3)),
            Some(assigned),
            None
        ));
    }

    #[test]
    fn blocks_ssrf_ranges_and_smtp() {
        for address in [
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "192.168.1.1",
            "100.64.0.1",
            "::1",
            "fe80::1",
            "fd00::1",
            "2001:db8::1",
        ] {
            assert!(!destination_is_allowed(address.parse().unwrap(), 443));
        }
        assert!(!destination_is_allowed("1.1.1.1".parse().unwrap(), 25));
        assert!(destination_is_allowed("1.1.1.1".parse().unwrap(), 443));
        assert!(destination_is_allowed(
            "2606:4700:4700::1111".parse().unwrap(),
            53
        ));
        assert!(destination_is_allowed(
            "64:ff9b::0808:0808".parse().unwrap(),
            53
        ));
    }

    #[test]
    fn ipv6_source_matches_exactly() {
        let assigned: Ipv6Addr = "fd66:6a75:7374::2".parse().unwrap();
        assert!(source_is_assigned(
            IpAddr::V6(assigned),
            None,
            Some(assigned)
        ));
    }
}
