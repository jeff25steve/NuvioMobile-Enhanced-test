use crate::ebml::{
    UNKNOWN, children, find, float, header, id, string, try_uint, uint, vint,
};
use crate::error::{Error, Result};
use crate::http::RangeReader;
use crate::keyframes::{Container, Keyframe, Keyframes};
use crate::track::{Track, TrackKind, Video};

/// The first read of a file, which usually covers everything before the first cluster.
pub const HEAD: u64 = 256 * 1024;

pub struct Segment {
    /// File offset that SeekHead and Cues positions count from.
    pub data_start: u64,
    pub scale_ns: u64,
    pub duration_ms: u64,
    pub tracks: Vec<Track>,
    seeks: Vec<(u32, u64)>,
}

#[derive(Debug, Clone, Copy)]
pub struct CuePoint {
    /// In segment ticks; see [`Segment::ms`].
    pub time: u64,
    pub track: u64,
    /// File offset of the cluster.
    pub cluster: u64,
    /// Offset of the block inside the cluster's body.
    pub relative: Option<u64>,
    pub duration: Option<u64>,
}

fn read_element(r: &mut RangeReader, pos: u64) -> Result<(u32, Vec<u8>)> {
    let head = r.read(pos, 64 * 1024)?;
    let (id, h, size) = header(&head, 0).ok_or(Error::Malformed("element header"))?;
    if size == UNKNOWN {
        return Err(Error::Unsupported("unknown-size element".into()));
    }
    let bad = || Error::Malformed("element bounds");
    let header_size = u64::try_from(h).map_err(|_| bad())?;
    let total = header_size.checked_add(size).ok_or_else(bad)?;
    let end = pos.checked_add(total).ok_or_else(bad)?;
    if r.size.is_some_and(|file_size| end > file_size) {
        return Err(bad());
    }
    if total <= head.len() as u64 {
        let end = usize::try_from(total).map_err(|_| bad())?;
        let body = head.get(h..end).ok_or_else(bad)?.to_vec();
        return Ok((id, body));
    }

    let tail_start = pos
        .checked_add(u64::try_from(head.len()).map_err(|_| bad())?)
        .ok_or_else(bad)?;
    let tail_len = total - head.len() as u64;
    let tail = r.read(tail_start, tail_len)?;
    if tail.len() as u64 != tail_len {
        return Err(bad());
    }
    let mut body = head.get(h..).ok_or_else(bad)?.to_vec();
    body.try_reserve_exact(tail.len()).map_err(|_| bad())?;
    body.extend(tail);
    Ok((id, body))
}

fn element_bounds(buf: &[u8], at: usize) -> Result<(u32, usize, usize)> {
    let bad = || Error::Malformed("element bounds");
    let (element, header_size, size) =
        header(buf, at).ok_or(Error::Malformed("element header"))?;
    if size == UNKNOWN {
        return Err(Error::Unsupported("unknown-size element".into()));
    }
    let body_start = at.checked_add(header_size).ok_or_else(bad)?;
    let body_size = usize::try_from(size).map_err(|_| bad())?;
    let end = body_start.checked_add(body_size).ok_or_else(bad)?;
    Ok((element, body_start, end))
}

fn block_payload_range(
    buf: &[u8],
    element: u32,
    body_start: usize,
    element_end: usize,
) -> Result<Option<(usize, usize)>> {
    let bad = || Error::Malformed("block group");
    match element {
        id::SIMPLE_BLOCK => Ok(Some((body_start, element_end))),
        id::BLOCK_GROUP => {
            let mut child_at = body_start;
            while child_at < element_end {
                let (child_id, header_size, child_size) =
                    header(buf, child_at).ok_or_else(bad)?;
                if child_size == UNKNOWN {
                    return Err(Error::Unsupported("unknown-size block group child".into()));
                }
                let child_start = child_at.checked_add(header_size).ok_or_else(bad)?;
                let child_len = usize::try_from(child_size).map_err(|_| bad())?;
                let child_end = child_start.checked_add(child_len).ok_or_else(bad)?;
                if child_end > element_end {
                    return Err(bad());
                }
                if child_id == id::BLOCK {
                    return Ok(Some((child_start, child_end)));
                }
                child_at = child_end;
            }
            Ok(None)
        }
        _ => Ok(None),
    }
}

fn cluster_time_ms(timestamp: u64, relative: i16, scale_ns: u64) -> Result<u64> {
    let bad = || Error::Malformed("cluster timestamp");
    let ticks = (i128::from(timestamp) + i128::from(relative)).max(0) as u128;
    let nanoseconds = ticks
        .checked_mul(u128::from(scale_ns))
        .ok_or_else(bad)?;
    let milliseconds = nanoseconds / 1_000_000;
    u64::try_from(milliseconds).map_err(|_| bad())
}

fn seek_entries(body: &[u8], data_start: u64) -> impl Iterator<Item = (u32, u64)> + '_ {
    children(body)
        .filter(|(i, _)| *i == id::SEEK)
        .filter_map(move |(_, seek)| {
            let seek_id = find(seek, id::SEEK_ID)?;
            // SeekID is a binary EBML Element ID, not an unsigned integer payload.
            if seek_id.is_empty() || seek_id.len() > 4 {
                return None;
            }
            let element_id = u32::try_from(uint(seek_id)).ok()?;
            let position = try_uint(find(seek, id::SEEK_POSITION)?)?;
            Some((element_id, data_start.checked_add(position)?))
        })
}

fn optional_uint(entry: &[u8], element: u32, default: u64) -> Option<u64> {
    match find(entry, element) {
        None => Some(default),
        Some(value) if value.is_empty() => Some(default),
        Some(value) => try_uint(value),
    }
}

fn flag(entry: &[u8], element: u32, default: bool) -> Option<bool> {
    optional_uint(entry, element, u64::from(default)).map(|value| value != 0)
}

fn parse_track(entry: &[u8]) -> Option<Track> {
    let kind = match find(entry, id::TRACK_TYPE).and_then(try_uint)? {
        1 => TrackKind::Video,
        2 => TrackKind::Audio,
        17 => TrackKind::Subtitle,
        _ => TrackKind::Other,
    };
    let mut t = Track::new(
        find(entry, id::TRACK_NUMBER).and_then(try_uint)?,
        kind,
        string(find(entry, id::CODEC_ID)?),
    );
    t.private = find(entry, id::CODEC_PRIVATE)
        .map(<[u8]>::to_vec)
        .unwrap_or_default();
    t.name = find(entry, id::NAME).map(string);
    t.language = find(entry, id::LANGUAGE_IETF)
        .or_else(|| find(entry, id::LANGUAGE))
        .map(string);
    t.enabled = flag(entry, id::FLAG_ENABLED, true)?;
    t.default = flag(entry, id::FLAG_DEFAULT, true)?;
    t.forced = flag(entry, id::FLAG_FORCED, false)?;
    t.hearing_impaired = flag(entry, id::FLAG_HEARING_IMPAIRED, false)?;
    t.commentary = flag(entry, id::FLAG_COMMENTARY, false)?;
    if let Some(encodings) = find(entry, id::CONTENT_ENCODINGS) {
        for (_, encoding) in children(encodings).filter(|(i, _)| *i == id::CONTENT_ENCODING) {
            if let Some(comp) = find(encoding, id::CONTENT_COMPRESSION)
                && optional_uint(comp, id::CONTENT_COMP_ALGO, 0)? == 3
            {
                t.header_strip = find(comp, id::CONTENT_COMP_SETTINGS)
                    .map(<[u8]>::to_vec)
                    .unwrap_or_default();
            }
        }
    }
    if let Some(video) = find(entry, id::VIDEO) {
        let mut v = Video::new(
            optional_uint(video, id::PIXEL_WIDTH, 0)?,
            optional_uint(video, id::PIXEL_HEIGHT, 0)?,
        );
        v.display_width = optional_uint(video, id::DISPLAY_WIDTH, v.width)?;
        v.display_height = optional_uint(video, id::DISPLAY_HEIGHT, v.height)?;
        if let Some(c) = find(video, id::COLOUR) {
            v.matrix = optional_uint(c, id::MATRIX_COEFFICIENTS, 2)?;
            v.range = optional_uint(c, id::RANGE, 0)?;
            v.transfer = optional_uint(c, id::TRANSFER_CHARACTERISTICS, 2)?;
            v.primaries = optional_uint(c, id::PRIMARIES, 2)?;
        }
        for (_, map) in children(entry).filter(|(i, _)| *i == id::BLOCK_ADDITION_MAPPING) {
            if matches!(
                find(map, id::BLOCK_ADD_ID_TYPE).and_then(try_uint),
                Some(0x64766343 | 0x64767643)
            ) && let Some(record) = find(map, id::BLOCK_ADD_ID_EXTRA_DATA)
            {
                v.set_dovi(record);
            }
        }
        t.video = Some(v);
    }
    Some(t)
}

impl Segment {
    pub fn open(r: &mut RangeReader) -> Result<Segment> {
        let head = r.read(0, HEAD)?;
        let (ebml, h, size) = header(&head, 0).ok_or(Error::Malformed("EBML header"))?;
        if ebml != id::EBML || size == UNKNOWN {
            return Err(Error::Unsupported("invalid EBML header".into()));
        }
        let bad = || Error::Malformed("element bounds");
        let ebml_end = h
            .checked_add(usize::try_from(size).map_err(|_| bad())?)
            .ok_or_else(bad)?;
        let (segment, segment_header_size, _) =
            header(&head, ebml_end).ok_or(Error::Malformed("segment"))?;
        if segment != id::SEGMENT {
            return Err(Error::Malformed("segment"));
        }
        let mut i = ebml_end
            .checked_add(segment_header_size)
            .ok_or_else(bad)?;
        let data_start = i as u64;
        let (mut info, mut tracks, mut seeks) = (None, None, Vec::new());
        while let Some((element, h, size)) = header(&head, i) {
            if element == id::CLUSTER || size == UNKNOWN {
                break;
            }
            let element_end = i
                .checked_add(h)
                .and_then(|body_start| {
                    usize::try_from(size)
                        .ok()
                        .and_then(|body_size| body_start.checked_add(body_size))
                })
                .ok_or_else(bad)?;
            let body = if element_end <= head.len() {
                head.get(i + h..element_end).ok_or_else(bad)?.to_vec()
            } else if matches!(element, id::INFO | id::TRACKS | id::SEEK_HEAD) {
                read_element(r, i as u64)?.1
            } else {
                break;
            };
            match element {
                id::SEEK_HEAD => seeks.extend(seek_entries(&body, data_start)),
                id::INFO => info = Some(body),
                id::TRACKS => tracks = Some(body),
                _ => {}
            }
            i = element_end;
        }
        // A SeekHead may point at a second one, often at the end of the file.
        let more: Vec<u64> = seeks
            .iter()
            .filter(|(e, _)| *e == id::SEEK_HEAD)
            .map(|&(_, p)| p)
            .collect();
        for pos in more {
            if let Ok((id::SEEK_HEAD, body)) = read_element(r, pos) {
                seeks.extend(seek_entries(&body, data_start));
            }
        }
        let mut segment = Segment {
            data_start,
            scale_ns: 1_000_000,
            duration_ms: 0,
            tracks: Vec::new(),
            seeks,
        };
        let info = match info {
            Some(b) => b,
            None => {
                read_element(
                    r,
                    segment
                        .position(id::INFO)
                        .ok_or(Error::Malformed("segment info"))?,
                )?
                .1
            }
        };
        let tracks = match tracks {
            Some(b) => b,
            None => {
                read_element(
                    r,
                    segment
                        .position(id::TRACKS)
                        .ok_or(Error::Malformed("tracks"))?,
                )?
                .1
            }
        };
        segment.scale_ns = optional_uint(&info, id::TIMESTAMP_SCALE, 1_000_000)
            .ok_or(Error::Malformed("timestamp scale"))?;
        if segment.scale_ns == 0 {
            return Err(Error::Malformed("timestamp scale"));
        }
        segment.duration_ms = find(&info, id::DURATION)
            .map_or(0.0, |d| float(d) * segment.scale_ns as f64 / 1e6)
            as u64;
        segment.tracks = children(&tracks)
            .filter(|(e, _)| *e == id::TRACK_ENTRY)
            .filter_map(|(_, e)| parse_track(e))
            .collect();
        Ok(segment)
    }

    /// File offset of a top-level element the SeekHead lists.
    pub fn position(&self, element: u32) -> Option<u64> {
        self.seeks
            .iter()
            .find(|(e, _)| *e == element)
            .map(|&(_, p)| p)
    }

    pub fn ms(&self, ticks: u64) -> u64 {
        (ticks as u128 * self.scale_ns as u128 / 1_000_000) as u64
    }

    /// Every cue of every track, in file order.
    pub fn cues(&self, r: &mut RangeReader) -> Result<Vec<CuePoint>> {
        let (_, cues) = read_element(r, self.position(id::CUES).ok_or(Error::NoIndex)?)?;
        let mut out = Vec::new();
        for (_, point) in children(&cues).filter(|(e, _)| *e == id::CUE_POINT) {
            let time = optional_uint(point, id::CUE_TIME, 0)
                .ok_or(Error::Malformed("cue time"))?;
            for (_, pos) in children(point).filter(|(e, _)| *e == id::CUE_TRACK_POSITIONS) {
                let (Some(track), Some(cluster)) = (
                    find(pos, id::CUE_TRACK),
                    find(pos, id::CUE_CLUSTER_POSITION),
                ) else {
                    continue;
                };
                let track = try_uint(track).ok_or(Error::Malformed("cue track"))?;
                let cluster = try_uint(cluster).ok_or(Error::Malformed("cue cluster position"))?;
                let relative = find(pos, id::CUE_RELATIVE_POSITION)
                    .map(|value| try_uint(value).ok_or(Error::Malformed("cue relative position")))
                    .transpose()?;
                let duration = find(pos, id::CUE_DURATION)
                    .map(|value| try_uint(value).ok_or(Error::Malformed("cue duration")))
                    .transpose()?;
                out.push(CuePoint {
                    time,
                    track,
                    cluster: self
                        .data_start
                        .checked_add(cluster)
                        .ok_or(Error::Malformed("cue cluster position"))?,
                    relative,
                    duration,
                });
            }
        }
        Ok(out)
    }

    pub fn keyframes(self, r: &mut RangeReader) -> Result<Keyframes> {
        let video = self
            .tracks
            .iter()
            .find(|t| t.kind == TrackKind::Video)
            .cloned()
            .ok_or(Error::Unsupported("no video track".into()))?;
        let mut entries: Vec<Keyframe> = self
            .cues(r)?
            .into_iter()
            .filter(|c| c.track == video.number)
            .map(|c| Keyframe {
                time_ms: self.ms(c.time),
                offset: c.cluster,
                relative: c.relative,
                size: None,
            })
            .collect();
        entries.sort_by_key(|k| k.time_ms);
        entries.dedup_by_key(|k| k.time_ms);
        if entries.is_empty() {
            return Err(Error::NoIndex);
        }
        Ok(Keyframes {
            container: Container::Matroska,
            track: video,
            duration_ms: self.duration_ms,
            scale_ns: self.scale_ns,
            entries,
        })
    }
}

/// The frame in the block at `at` of `buf`, read from `base`, completing a short read.
fn block(
    k: &Keyframes,
    r: &mut RangeReader,
    base: u64,
    mut buf: Vec<u8>,
    at: usize,
) -> Result<Vec<u8>> {
    let (element, body_start, end) = element_bounds(&buf, at)?;
    if end > buf.len() {
        let bad = || Error::Malformed("block data");
        let have = u64::try_from(buf.len()).map_err(|_| bad())?;
        let read_at = base.checked_add(have).ok_or_else(bad)?;
        let missing = end.checked_sub(buf.len()).ok_or_else(bad)?;
        let tail = r.read(
            read_at,
            u64::try_from(missing).map_err(|_| bad())?,
        )?;
        if tail.len() != missing {
            return Err(bad());
        }
        buf.try_reserve_exact(tail.len()).map_err(|_| bad())?;
        buf.extend(tail);
    }
    let body = buf.get(body_start..end).ok_or(Error::Malformed("block data"))?;
    let block = match element {
        id::SIMPLE_BLOCK => body,
        id::BLOCK_GROUP => find(body, id::BLOCK).ok_or(Error::Malformed("block group"))?,
        _ => return Err(Error::Malformed("block")),
    };
    let (track, i) = vint(block, 0, false).ok_or(Error::Malformed("block"))?;
    if track != k.track.number {
        return Err(Error::Malformed("block of another track"));
    }
    let flags_at = i.checked_add(2).ok_or(Error::Malformed("block"))?;
    if block.get(flags_at).ok_or(Error::Malformed("block"))? & 0x06 != 0 {
        return Err(Error::Unsupported("laced video block".into()));
    }
    let frame_at = i.checked_add(3).ok_or(Error::Malformed("block"))?;
    let mut frame = k.track.header_strip.clone();
    frame.extend_from_slice(block.get(frame_at..).ok_or(Error::Malformed("block"))?);
    Ok(frame)
}

pub(crate) fn keyframe(
    k: &Keyframes,
    r: &mut RangeReader,
    e: &Keyframe,
    guess: u64,
) -> Result<Vec<u8>> {
    let Some(rel) = e.relative else {
        return scan_cluster(k, r, e);
    };
    // A cluster header is at most 12 bytes; read it with the block when they are close.
    let near = rel < 256 * 1024;
    let requested = if near {
        12u64
            .checked_add(rel)
            .and_then(|length| length.checked_add(guess))
            .ok_or(Error::Malformed("cluster position"))?
    } else {
        12
    };
    let buf = r.read(e.offset, requested)?;
    let (element, h, _) = header(&buf, 0).ok_or(Error::Malformed("cluster"))?;
    if element != id::CLUSTER {
        return Err(Error::Malformed("cluster"));
    }
    if near {
        let relative = usize::try_from(rel).map_err(|_| Error::Malformed("cluster position"))?;
        let at = h.checked_add(relative).ok_or(Error::Malformed("cluster position"))?;
        return block(k, r, e.offset, buf, at);
    }
    let base = e
        .offset
        .checked_add(u64::try_from(h).map_err(|_| Error::Malformed("cluster position"))?)
        .and_then(|offset| offset.checked_add(rel))
        .ok_or(Error::Malformed("cluster position"))?;
    let buf = r.read(base, guess)?;
    block(k, r, base, buf, 0)
}

fn scan_cluster(k: &Keyframes, r: &mut RangeReader, e: &Keyframe) -> Result<Vec<u8>> {
    let buf = r.read(e.offset, 512 * 1024)?;
    let (element, header_size, _) =
        header(&buf, 0).ok_or(Error::Malformed("cluster"))?;
    if element != id::CLUSTER {
        return Err(Error::Malformed("cluster"));
    }

    let mut i = header_size;
    let mut timestamp = 0u64;
    while i < buf.len() {
        let Some((element, header_size, size)) = header(&buf, i) else {
            break;
        };
        if size == UNKNOWN {
            return Err(Error::Unsupported("unknown-size cluster child".into()));
        }
        let body_start = i
            .checked_add(header_size)
            .ok_or(Error::Malformed("cluster element"))?;
        let body_size = usize::try_from(size)
            .map_err(|_| Error::Malformed("cluster element"))?;
        let element_end = body_start
            .checked_add(body_size)
            .ok_or(Error::Malformed("cluster element"))?;
        // The fixed scan window may end in a later element. Do not inspect a partial one.
        if element_end > buf.len() {
            break;
        }

        match element {
            id::CLUSTER_TIMESTAMP => {
                if body_size > 8 {
                    return Err(Error::Malformed("cluster timestamp"));
                }
                timestamp = try_uint(&buf[body_start..element_end])
                    .ok_or(Error::Malformed("cluster timestamp"))?;
            }
            id::SIMPLE_BLOCK | id::BLOCK_GROUP => {
                if let Some((block_start, block_end)) =
                    block_payload_range(&buf, element, body_start, element_end)?
                {
                    let block_data = buf
                        .get(block_start..block_end)
                        .ok_or(Error::Malformed("cluster block"))?;
                    if let Some((track, timestamp_at)) = vint(block_data, 0, false)
                        && track == k.track.number
                    {
                        let timestamp_end = timestamp_at
                            .checked_add(2)
                            .ok_or(Error::Malformed("cluster block"))?;
                        if let Some(relative_bytes) = block_data.get(timestamp_at..timestamp_end) {
                            let relative = i16::from_be_bytes([
                                relative_bytes[0],
                                relative_bytes[1],
                            ]);
                            let time_ms = cluster_time_ms(timestamp, relative, k.scale_ns)?;
                            if time_ms.saturating_add(1) >= e.time_ms {
                                return block(k, r, e.offset, buf, i);
                            }
                        }
                    }
                }
            }
            _ => {}
        }
        i = element_end;
    }
    Err(Error::Unsupported(
        "keyframe not near its cluster start".into(),
    ))
}

#[cfg(test)]
mod tests {
    use super::{
        block, block_payload_range, cluster_time_ms, element_bounds,
    };
    use crate::ebml::id;
    use crate::http::{Options, RangeReader};
    use crate::keyframes::{Container, Keyframes};
    use crate::track::{Track, TrackKind};
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_media_file(bytes: &[u8]) -> String {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "nuvio-matroska-block-{}-{nonce}.mkv",
            std::process::id(),
        ));
        fs::write(&path, bytes).unwrap();
        path.to_string_lossy().into_owned()
    }

    fn keyframes() -> Keyframes {
        Keyframes {
            container: Container::Matroska,
            track: Track::new(1, TrackKind::Video, "V_TEST".to_string()),
            duration_ms: 0,
            scale_ns: 1_000_000,
            entries: Vec::new(),
        }
    }

    #[test]
    fn element_bounds_reports_the_checked_block_body_range() {
        let bytes = [0xA3, 0x87, 0x81, 0, 0, 0, 0xAA, 0xBB, 0xCC];
        assert_eq!(
            element_bounds(&bytes, 0).unwrap(),
            (id::SIMPLE_BLOCK, 2, 9),
        );
    }

    #[test]
    fn block_reads_a_partial_buffer_tail_before_returning_the_frame() {
        let full = [0xA3, 0x87, 0x81, 0, 0, 0, 0xAA, 0xBB, 0xCC];
        let path = temp_media_file(&full);
        let result = (|| {
            let mut reader = RangeReader::new(&path, Options::default()).unwrap();
            block(&keyframes(), &mut reader, 0, full[..6].to_vec(), 0)
        })();
        fs::remove_file(path).unwrap();
        assert_eq!(result.unwrap(), vec![0xAA, 0xBB, 0xCC]);
    }

    #[test]
    fn block_returns_an_error_when_the_declared_tail_is_truncated() {
        let truncated = [0xA3, 0x87, 0x81, 0, 0, 0, 0xAA];
        let path = temp_media_file(&truncated);
        let result = (|| {
            let mut reader = RangeReader::new(&path, Options::default()).unwrap();
            block(&keyframes(), &mut reader, 0, truncated[..6].to_vec(), 0)
        })();
        fs::remove_file(path).unwrap();
        assert!(result.is_err());
    }

    #[test]
    fn unknown_sized_blocks_are_rejected() {
        let bytes = [0xA3, 0xFF];
        assert!(element_bounds(&bytes, 0).is_err());
    }

    #[test]
    fn cluster_time_uses_wide_timestamp_arithmetic_and_clamps_negative_values() {
        assert_eq!(cluster_time_ms(100, -10, 1_000_000).unwrap(), 90);
        assert_eq!(cluster_time_ms(5, -10, 1_000_000).unwrap(), 0);
        assert!(cluster_time_ms(u64::MAX, 1, u64::MAX).is_err());
    }


    #[test]
    fn finds_the_block_inside_a_block_group_after_other_elements() {
        // BlockGroup payload: ReferenceBlock, then Block (track 1, timestamp, flags).
        let bytes = [
            0xA0, 0x8B,
            0xFB, 0x81, 0x00,
            0xA1, 0x86, 0x81, 0x00, 0x00, 0x00, 0xAA, 0xBB,
        ];
        assert_eq!(
            block_payload_range(&bytes, id::BLOCK_GROUP, 2, bytes.len()).unwrap(),
            Some((7, 13)),
        );
    }

    #[test]
    fn rejects_a_block_group_child_that_exceeds_its_parent() {
        let bytes = [0xA0, 0x83, 0xA1, 0x86, 0x81];
        assert!(block_payload_range(&bytes, id::BLOCK_GROUP, 2, bytes.len()).is_err());
    }

    #[test]
    fn simple_block_payload_range_excludes_its_element_header() {
        let bytes = [0xA3, 0x87, 0x81, 0, 0, 0, 0xAA, 0xBB, 0xCC];
        assert_eq!(
            block_payload_range(&bytes, id::SIMPLE_BLOCK, 2, bytes.len()).unwrap(),
            Some((2, 9)),
        );
    }

}

#[cfg(test)]
mod unsigned_integer_tests {
    use super::{flag, optional_uint, parse_track};
    use crate::ebml::id;

    fn append_element(output: &mut Vec<u8>, element_id: u8, payload: &[u8]) {
        assert!(payload.len() < 127);
        output.push(element_id);
        output.push(0x80 | payload.len() as u8);
        output.extend_from_slice(payload);
    }

    #[test]
    fn optional_integer_uses_default_for_missing_or_empty_elements() {
        assert_eq!(optional_uint(&[], id::TIMESTAMP_SCALE, 1_000_000), Some(1_000_000));
        let empty = [0x2A, 0x80];
        assert_eq!(optional_uint(&empty, 0x2A, 42), Some(42));
    }

    #[test]
    fn optional_integer_rejects_payloads_wider_than_ebml_uinteger_limit() {
        let mut element = Vec::new();
        append_element(&mut element, 0xD7, &[0, 1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(optional_uint(&element, id::TRACK_NUMBER, 1), None);
    }

    #[test]
    fn malformed_track_number_does_not_wrap_into_a_valid_track() {
        let mut entry = Vec::new();
        append_element(&mut entry, 0x83, &[1]); // TrackType = video.
        append_element(&mut entry, 0xD7, &[0, 1, 2, 3, 4, 5, 6, 7, 8]); // Nine-byte TrackNumber.
        append_element(&mut entry, 0x86, b"V_TEST"); // CodecID.
        assert!(parse_track(&entry).is_none());
    }

    #[test]
    fn empty_track_flags_resolve_to_their_declared_defaults() {
        let enabled = [0xB9, 0x80];
        let forced = [0x55, 0xAA, 0x80];
        assert_eq!(flag(&enabled, id::FLAG_ENABLED, true), Some(true));
        assert_eq!(flag(&forced, id::FLAG_FORCED, false), Some(false));
    }
}

