use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::error::{Error, Result};

#[derive(Clone)]
pub struct Options {
    pub headers: Vec<(String, String)>,
    pub timeout: Duration,
    pub connect_timeout: Duration,
    pub max_bytes: Option<u64>,
    pub max_requests: Option<u32>,
    pub deadline: Option<Instant>,
    /// Trusted roots as PEM, replacing the bundled ones.
    pub ca_pem: Option<Vec<u8>>,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            headers: Vec::new(),
            timeout: Duration::from_secs(30),
            connect_timeout: Duration::from_secs(10),
            max_bytes: None,
            max_requests: None,
            deadline: None,
            ca_pem: None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ContentRange {
    start: u64,
    end: u64,
    total: Option<u64>,
}

fn parse_content_range(value: &str) -> Option<ContentRange> {
    let (unit, range_and_total) = value.trim().split_once(' ')?;
    if !unit.eq_ignore_ascii_case("bytes") {
        return None;
    }
    let (range, total) = range_and_total.trim().split_once('/')?;
    let (start, end) = range.split_once('-')?;
    let start = start.parse::<u64>().ok()?;
    let end = end.parse::<u64>().ok()?;
    if start > end {
        return None;
    }
    let total = if total == "*" {
        None
    } else {
        let total = total.parse::<u64>().ok()?;
        if total <= end {
            return None;
        }
        Some(total)
    };
    Some(ContentRange { start, end, total })
}

/// Accepts only the requested byte interval, allowing a shorter interval only when
/// Content-Range proves that it reaches the end of the representation.
fn validate_content_range(
    value: &str,
    requested_start: u64,
    requested_len: u64,
) -> Option<ContentRange> {
    let requested_end = requested_start.checked_add(requested_len.checked_sub(1)?)?;
    let range = parse_content_range(value)?;
    if range.start != requested_start || range.end > requested_end {
        return None;
    }
    if range.end < requested_end && range.total != Some(range.end.checked_add(1)?) {
        return None;
    }
    Some(range)
}

/// Range reads of one URL, or of a local path.
pub struct RangeReader {
    agent: ureq::Agent,
    url: String,
    /// The URL given, which `url` follows redirects from.
    origin: String,
    options: Options,
    last: Option<(u64, Vec<u8>)>,
    pub size: Option<u64>,
    pub requests: u32,
    pub bytes: u64,
    pub waited: Duration,
}

impl RangeReader {
    pub fn new(url: &str, options: Options) -> Result<RangeReader> {
        let mut config = ureq::Agent::config_builder()
            .timeout_global(Some(options.timeout))
            .timeout_connect(Some(options.connect_timeout))
            .http_status_as_error(false);
        if let Some(pem) = &options.ca_pem {
            let certs: Vec<_> = ureq::tls::parse_pem(pem)
                .filter_map(|item| match item {
                    Ok(ureq::tls::PemItem::Certificate(c)) => Some(c),
                    _ => None,
                })
                .collect();
            if certs.is_empty() {
                return Err(Error::Malformed("CA bundle"));
            }
            let roots = ureq::tls::RootCerts::Specific(Arc::new(certs));
            config = config.tls_config(ureq::tls::TlsConfig::builder().root_certs(roots).build());
        }
        Ok(RangeReader {
            agent: config.build().into(),
            url: url.to_string(),
            origin: url.to_string(),
            options,
            last: None,
            size: None,
            requests: 0,
            bytes: 0,
            waited: Duration::ZERO,
        })
    }

    /// Another reader of the same file, on this one's connections.
    pub fn fork(&self) -> RangeReader {
        RangeReader {
            agent: self.agent.clone(),
            url: self.url.clone(),
            origin: self.origin.clone(),
            options: self.options.clone(),
            last: None,
            size: self.size,
            requests: 0,
            bytes: 0,
            waited: Duration::ZERO,
        }
    }

    /// Up to `len` bytes from `start`; fewer only at the end of the file.
    pub fn read(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        let mut len = len;
        if let Some(size) = self.size {
            if start >= size {
                return Ok(Vec::new());
            }
            len = len.min(size - start);
        }
        if len == 0 {
            return Ok(Vec::new());
        }
        let requested_end = start.checked_add(len).ok_or(Error::Budget)?;
        if let Some((at, buf)) = &self.last
            && start >= *at
            && at
                .checked_add(buf.len() as u64)
                .is_some_and(|end| requested_end <= end)
        {
            let from = usize::try_from(start - at).map_err(|_| Error::Budget)?;
            let count = usize::try_from(len).map_err(|_| Error::Budget)?;
            return Ok(buf[from..from + count].to_vec());
        }
        let o = &self.options;
        let projected_bytes = self.bytes.checked_add(len).ok_or(Error::Budget)?;
        if o.max_requests.is_some_and(|m| self.requests >= m)
            || o.max_bytes.is_some_and(|m| projected_bytes > m)
            || o.deadline.is_some_and(|d| Instant::now() >= d)
        {
            return Err(Error::Budget);
        }
        let t = Instant::now();
        let body = if self.url.starts_with("http://") || self.url.starts_with("https://") {
            self.fetch(start, len)?
        } else {
            self.read_file(start, len)?
        };
        self.requests = self.requests.checked_add(1).ok_or(Error::Budget)?;
        self.bytes = self.bytes.checked_add(body.len() as u64).ok_or(Error::Budget)?;
        self.waited += t.elapsed();
        self.last = Some((start, body.clone()));
        Ok(body)
    }

    /// A redirect's target can expire, so a failed read of one starts again from the URL given.
    fn fetch(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        match self.fetch_from(start, len) {
            Err(_) if self.url != self.origin => {
                self.url = self.origin.clone();
                self.fetch_from(start, len)
            }
            read => read,
        }
    }

    fn fetch_from(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        let end = start
            .checked_add(len.checked_sub(1).ok_or(Error::Budget)?)
            .ok_or(Error::Budget)?;
        let body_limit = len.checked_add(1).ok_or(Error::Budget)?;
        let mut request = self
            .agent
            .get(&self.url)
            .header("Range", format!("bytes={start}-{end}"));
        for (key, value) in &self.options.headers {
            // Range is owned by the reader; caller headers must not replace or duplicate it.
            if !key.eq_ignore_ascii_case("range") {
                request = request.header(key, value);
            }
        }
        let response = request.call().map_err(|e| Error::Http(e.to_string()))?;
        if response.status() != 206 {
            return Err(Error::Http(format!("range answered {}", response.status())));
        }
        let content_range = response
            .headers()
            .get("content-range")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| validate_content_range(value, start, len))
            .ok_or_else(|| Error::Http("missing or mismatched Content-Range".into()))?;
        if self
            .size
            .zip(content_range.total)
            .is_some_and(|(known, received)| known != received)
        {
            return Err(Error::Http("remote representation size changed".into()));
        }
        let expected_body_len = content_range
            .end
            .checked_sub(content_range.start)
            .and_then(|size| size.checked_add(1))
            .ok_or(Error::Budget)?;
        let final_url = ureq::ResponseExt::get_uri(&response).to_string();
        let body = response
            .into_body()
            .into_with_config()
            // One extra byte detects a server that ignores or exceeds the requested range.
            .limit(body_limit)
            .read_to_vec()
            .map_err(|e| Error::Http(e.to_string()))?;
        if body.len() as u64 != expected_body_len {
            return Err(Error::Http("range body length does not match Content-Range".into()));
        }
        if let Some(total) = content_range.total {
            self.size = Some(total);
        }
        self.url = final_url;
        Ok(body)
    }

    fn read_file(&mut self, start: u64, len: u64) -> Result<Vec<u8>> {
        use std::io::{Read, Seek, SeekFrom};
        let io = |e: std::io::Error| Error::Http(e.to_string());
        let mut f = std::fs::File::open(&self.url).map_err(io)?;
        self.size = Some(f.metadata().map_err(io)?.len());
        f.seek(SeekFrom::Start(start)).map_err(io)?;
        let capacity = usize::try_from(len).map_err(|_| Error::Budget)?;
        let mut body = Vec::new();
        body.try_reserve_exact(capacity).map_err(|_| Error::Budget)?;
        f.take(len).read_to_end(&mut body).map_err(io)?;
        Ok(body)
    }
}

#[cfg(test)]
mod tests {
    use super::{ContentRange, parse_content_range, validate_content_range};

    #[test]
    fn parses_a_valid_single_byte_range() {
        assert_eq!(
            parse_content_range("bytes 10-19/100"),
            Some(ContentRange {
                start: 10,
                end: 19,
                total: Some(100),
            }),
        );
    }

    #[test]
    fn accepts_unknown_total_only_when_the_full_requested_range_is_returned() {
        assert_eq!(
            validate_content_range("bytes 10-19/*", 10, 10),
            Some(ContentRange {
                start: 10,
                end: 19,
                total: None,
            }),
        );
        assert_eq!(validate_content_range("bytes 10-18/*", 10, 10), None);
    }

    #[test]
    fn allows_a_short_range_only_when_it_reaches_the_known_end_of_file() {
        assert_eq!(
            validate_content_range("bytes 90-99/100", 90, 20),
            Some(ContentRange {
                start: 90,
                end: 99,
                total: Some(100),
            }),
        );
        assert_eq!(validate_content_range("bytes 90-99/*", 90, 20), None);
        assert_eq!(validate_content_range("bytes 90-98/100", 90, 20), None);
    }

    #[test]
    fn rejects_wrong_offsets_invalid_lengths_and_integer_overflow() {
        assert_eq!(validate_content_range("bytes 11-19/100", 10, 10), None);
        assert_eq!(validate_content_range("bytes 10-20/100", 10, 10), None);
        assert_eq!(parse_content_range("items 10-19/100"), None);
        assert_eq!(parse_content_range("bytes 20-10/100"), None);
        assert_eq!(parse_content_range("bytes 10-99/99"), None);
        assert_eq!(
            parse_content_range("bytes 18446744073709551616-18446744073709551616/*"),
            None,
        );
        assert_eq!(validate_content_range("bytes 0-0/*", u64::MAX, 2), None);
        assert_eq!(validate_content_range("bytes 0-0/*", 0, 0), None);
    }
}
