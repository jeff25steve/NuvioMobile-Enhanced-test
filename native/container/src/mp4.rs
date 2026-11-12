use crate::error::{Error, Result};
use crate::http::RangeReader;
use crate::keyframes::{Container, Keyframe, Keyframes};
use crate::track::{Track, TrackKind, Video};

pub(crate) fn u16be(b: &[u8], i: usize) -> Option<u64> {
    let end = i.checked_add(2)?;
    Some(u16::from_be_bytes(b.get(i..end)?.try_into().ok()?) as u64)
}

pub(crate) fn u32be(b: &[u8], i: usize) -> Option<u64> {
    let end = i.checked_add(4)?;
    Some(u32::from_be_bytes(b.get(i..end)?.try_into().ok()?) as u64)
}

pub(crate) fn u64be(b: &[u8], i: usize) -> Option<u64> {
    let end = i.checked_add(8)?;
    Some(u64::from_be_bytes(b.get(i..end)?.try_into().ok()?))
}

fn table_entry_offset(base: usize, index: usize, stride: usize) -> Option<usize> {
    base.checked_add(index.checked_mul(stride)?)
}

fn parse_sync_samples(stss: Option<&[u8]>, sample_count: u64) -> Result<Option<Vec<u64>>> {
    let Some(stss) = stss else {
        return Ok(None);
    };
    let bad = || Error::Malformed("sample tables");
    if stss.first() != Some(&0) {
        return Err(bad());
    }
    let entry_count = usize::try_from(u32be(stss, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    let required_len = entry_count
        .checked_mul(4)
        .and_then(|bytes| bytes.checked_add(8))
        .ok_or_else(bad)?;
    if stss.len() < required_len {
        return Err(bad());
    }
    let mut samples = Vec::new();
    samples.try_reserve_exact(entry_count).map_err(|_| bad())?;
    let mut previous = 0u64;
    for index in 0..entry_count {
        let offset = table_entry_offset(8, index, 4).ok_or_else(bad)?;
        let one_based = u32be(stss, offset).ok_or_else(bad)?;
        if one_based == 0 || one_based > sample_count || one_based <= previous {
            return Err(bad());
        }
        samples.push(one_based - 1);
        previous = one_based;
    }
    Ok(Some(samples))
}

fn sample_time_ms(
    dts: u64,
    composition_offset: i64,
    composition_shift: i64,
    timescale: u64,
) -> Result<u64> {
    let bad = || Error::Malformed("sample timestamps");
    if timescale == 0 {
        return Err(bad());
    }
    // Keep decode time independent: cslg shifts only the composition/presentation axis.
    let presentation_time = (i128::from(dts)
        + i128::from(composition_offset)
        + i128::from(composition_shift))
    .max(0) as u128;
    let milliseconds = presentation_time
        .checked_mul(1_000)
        .ok_or_else(bad)?
        / u128::from(timescale);
    u64::try_from(milliseconds).map_err(|_| bad())
}

fn parse_sample_to_chunk(
    stsc: &[u8],
    chunk_count: usize,
    sample_count: u64,
) -> Result<Vec<(u64, u64)>> {
    let bad = || Error::Malformed("sample tables");
    if stsc.first() != Some(&0) {
        return Err(bad());
    }
    let chunk_count = u64::try_from(chunk_count).map_err(|_| bad())?;
    let entry_count = usize::try_from(u32be(stsc, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    if entry_count == 0 || chunk_count == 0 {
        return Err(bad());
    }
    let required_len = entry_count
        .checked_mul(12)
        .and_then(|bytes| bytes.checked_add(8))
        .ok_or_else(bad)?;
    if stsc.len() < required_len {
        return Err(bad());
    }

    let mut runs = Vec::new();
    runs.try_reserve_exact(entry_count).map_err(|_| bad())?;
    let mut previous_first_chunk = 0u64;
    for index in 0..entry_count {
        let first_chunk = u32be(
            stsc,
            table_entry_offset(8, index, 12).ok_or_else(bad)?,
        )
        .ok_or_else(bad)?;
        let samples_per_chunk = u32be(
            stsc,
            table_entry_offset(12, index, 12).ok_or_else(bad)?,
        )
        .ok_or_else(bad)?;
        let description = u32be(
            stsc,
            table_entry_offset(16, index, 12).ok_or_else(bad)?,
        )
        .ok_or_else(bad)?;

        if first_chunk == 0
            || first_chunk > chunk_count
            || samples_per_chunk == 0
            || description == 0
            || (index == 0 && first_chunk != 1)
            || (index > 0 && first_chunk <= previous_first_chunk)
        {
            return Err(bad());
        }
        runs.push((first_chunk, samples_per_chunk));
        previous_first_chunk = first_chunk;
    }

    let mut mapped_samples = 0u64;
    for (index, &(first_chunk, samples_per_chunk)) in runs.iter().enumerate() {
        let end_exclusive = match runs.get(index + 1) {
            Some((next_first_chunk, _)) => *next_first_chunk,
            None => chunk_count.checked_add(1).ok_or_else(bad)?,
        };
        let chunks_in_run = end_exclusive.checked_sub(first_chunk).ok_or_else(bad)?;
        let run_samples = chunks_in_run
            .checked_mul(samples_per_chunk)
            .ok_or_else(bad)?;
        mapped_samples = mapped_samples.checked_add(run_samples).ok_or_else(bad)?;
    }
    if mapped_samples != sample_count {
        return Err(bad());
    }
    Ok(runs)
}

/// (type, body) of each box in `b`.
pub fn boxes(b: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut i = 0;
    std::iter::from_fn(move || {
        let size = u32be(b, i)?;
        let kind: [u8; 4] = b.get(i + 4..i + 8)?.try_into().ok()?;
        let (head, size) = match size {
            1 => (16, u64be(b, i + 8)?),
            0 => (8, (b.len() - i) as u64),
            n => (8, n),
        };
        let end = i.checked_add(usize::try_from(size).ok()?)?;
        if size < head || end > b.len() {
            return None;
        }
        let body = &b[i + head as usize..end];
        i = end;
        Some((kind, body))
    })
}

pub fn child<'a>(b: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    boxes(b).find(|(k, _)| k == kind).map(|(_, v)| v)
}

pub(crate) fn path<'a>(b: &'a [u8], kinds: &[&[u8; 4]]) -> Option<&'a [u8]> {
    kinds.iter().try_fold(b, |b, k| child(b, k))
}

fn parse_box_header(head: &[u8]) -> Result<([u8; 4], u64, u64)> {
    let size32 = u32be(head, 0).ok_or(Error::Malformed("MP4 box header"))?;
    let kind: [u8; 4] = head
        .get(4..8)
        .and_then(|value| value.try_into().ok())
        .ok_or(Error::Malformed("MP4 box header"))?;
    let (header_size, size) = match size32 {
        1 => (
            16,
            u64be(head, 8).ok_or(Error::Malformed("MP4 extended box header"))?,
        ),
        0 => return Err(Error::Malformed("MP4 box extends to EOF")),
        size => (8, size),
    };
    if size < header_size {
        return Err(Error::Malformed("MP4 box size"));
    }
    Ok((kind, size, header_size))
}

/// QuickTime movie files identify their preferred file format with the `qt  ` major brand.
fn is_quicktime_brand(major_brand: &[u8]) -> bool {
    major_brand == b"qt  "
}

fn moov_with_format(r: &mut RangeReader) -> Result<(Vec<u8>, bool)> {
    let mut pos = 0u64;
    let mut quicktime_format = false;
    loop {
        let head = r.read(pos, 16)?;
        let (kind, size, header_size) = parse_box_header(&head)?;
        if pos == 0 && kind != *b"ftyp" {
            return Err(Error::Unsupported("not MP4".into()));
        }
        if kind == *b"ftyp" && pos == 0 {
            let payload_size = size
                .checked_sub(header_size)
                .ok_or(Error::Malformed("MP4 file type size"))?;
            if payload_size < 8 || (payload_size - 8) % 4 != 0 {
                return Err(Error::Malformed("MP4 file type box"));
            }
            let brand_at = pos
                .checked_add(header_size)
                .ok_or(Error::Malformed("MP4 file type offset"))?;
            let major_brand = r.read(brand_at, 4)?;
            if major_brand.len() != 4 {
                return Err(Error::Malformed("MP4 file type brand"));
            }
            quicktime_format = is_quicktime_brand(&major_brand);
        }
        if kind == *b"moov" {
            let body_start = pos
                .checked_add(header_size)
                .ok_or(Error::Malformed("MP4 box offset"))?;
            let body_size = size - header_size;
            let body = r.read(body_start, body_size)?;
            if body.len() as u64 != body_size {
                return Err(Error::Malformed("MP4 moov length"));
            }
            return Ok((body, quicktime_format));
        }
        if kind == *b"moof" {
            return Err(Error::Unsupported("fragmented MP4".into()));
        }
        pos = pos.checked_add(size).ok_or(Error::Malformed("MP4 box offset"))?;
    }
}

/// The moov box's body, before or after the media data.
pub fn moov(r: &mut RangeReader) -> Result<Vec<u8>> {
    moov_with_format(r).map(|(body, _)| body)
}

fn parse_composition_offsets(
    ctts: Option<&[u8]>,
    quicktime_format: bool,
    expected_samples: usize,
) -> Result<Option<Vec<(u64, i64)>>> {
    let Some(ctts) = ctts else {
        return Ok(None);
    };
    let bad = || Error::Malformed("composition offset table");
    if ctts.len() < 8 || !matches!(ctts[0], 0 | 1) || ctts[1..4] != [0, 0, 0] {
        return Err(bad());
    }
    let entry_count = usize::try_from(u32be(ctts, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    let expected_len = entry_count
        .checked_mul(8)
        .and_then(|bytes| bytes.checked_add(8))
        .ok_or_else(bad)?;
    if ctts.len() != expected_len {
        return Err(bad());
    }

    let expected_samples = u64::try_from(expected_samples).map_err(|_| bad())?;
    let mut runs = Vec::new();
    runs.try_reserve_exact(entry_count).map_err(|_| bad())?;
    let mut covered_samples = 0u64;
    for index in 0..entry_count {
        let offset = table_entry_offset(8, index, 8).ok_or_else(bad)?;
        let sample_count = u32be(ctts, offset).ok_or_else(bad)?;
        let raw_offset = u32be(
            ctts,
            table_entry_offset(offset, 1, 4).ok_or_else(bad)?,
        )
        .ok_or_else(bad)?;
        if sample_count == 0 {
            return Err(bad());
        }
        covered_samples = covered_samples
            .checked_add(sample_count)
            .filter(|&total| total <= expected_samples)
            .ok_or_else(bad)?;

        // QuickTime's ctts atom defines a signed compositionOffset. ISO BMFF
        // version 0 defines it as unsigned; ISO BMFF version 1 defines it as signed.
        let composition_offset = if quicktime_format || ctts[0] == 1 {
            i64::from(raw_offset as u32 as i32)
        } else {
            i64::from(raw_offset as u32)
        };
        runs.push((sample_count, composition_offset));
    }
    if covered_samples != expected_samples {
        return Err(bad());
    }
    Ok(Some(runs))
}

fn parse_media_header(mdhd: &[u8]) -> Result<(u64, Option<u64>)> {
    let bad = || Error::Malformed("media header");
    if mdhd.get(1..4) != Some(&[0, 0, 0][..]) {
        return Err(bad());
    }
    let (timescale, duration) = match mdhd.first() {
        Some(&0) if mdhd.len() == 24 => (
            u32be(mdhd, 12),
            u32be(mdhd, 16).filter(|&value| value != u64::from(u32::MAX)),
        ),
        Some(&1) if mdhd.len() == 36 => (
            u32be(mdhd, 20),
            u64be(mdhd, 24).filter(|&value| value != u64::MAX),
        ),
        _ => return Err(bad()),
    };
    let timescale = timescale.filter(|&value| value > 0).ok_or_else(bad)?;
    Ok((timescale, duration))
}

fn parse_composition_to_decode(cslg: Option<&[u8]>) -> Result<Option<i64>> {
    let Some(cslg) = cslg else {
        return Ok(None);
    };
    let bad = || Error::Malformed("composition to decode table");
    if cslg.len() < 4 || !matches!(cslg[0], 0 | 1) || cslg[1..4] != [0, 0, 0] {
        return Err(bad());
    }

    let field_width: usize = if cslg[0] == 0 { 4 } else { 8 };
    let expected_len = field_width
        .checked_mul(5)
        .and_then(|bytes| bytes.checked_add(4))
        .ok_or_else(bad)?;
    if cslg.len() != expected_len {
        return Err(bad());
    }

    let signed_field = |index: usize| {
        let offset = 4usize.checked_add(index.checked_mul(field_width)?)?;
        if field_width == 4 {
            u32be(cslg, offset).map(|value| i64::from(value as u32 as i32))
        } else {
            u64be(cslg, offset).map(|value| value as i64)
        }
    };
    let composition_to_dts_shift = signed_field(0).ok_or_else(bad)?;
    let least_decode_to_display_delta = signed_field(1).ok_or_else(bad)?;
    let greatest_decode_to_display_delta = signed_field(2).ok_or_else(bad)?;
    let composition_start_time = signed_field(3).ok_or_else(bad)?;
    let composition_end_time = signed_field(4).ok_or_else(bad)?;

    // The end-time value 0 means unknown; otherwise the declared bounds must be ordered.
    if least_decode_to_display_delta > greatest_decode_to_display_delta
        || (composition_end_time != 0 && composition_start_time > composition_end_time)
    {
        return Err(bad());
    }
    Ok(Some(composition_to_dts_shift))
}

fn parse_decode_time_table(
    stts: &[u8],
    expected_samples: usize,
) -> Result<(Vec<(u64, u64)>, u64)> {
    let bad = || Error::Malformed("decode time table");
    if stts.len() < 8 || stts[0] != 0 || stts[1..4] != [0, 0, 0] {
        return Err(bad());
    }
    let entry_count = usize::try_from(u32be(stts, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    let expected_len = entry_count
        .checked_mul(8)
        .and_then(|bytes| bytes.checked_add(8))
        .ok_or_else(bad)?;
    if stts.len() != expected_len {
        return Err(bad());
    }

    let expected_samples = u64::try_from(expected_samples).map_err(|_| bad())?;
    let mut runs = Vec::new();
    runs.try_reserve_exact(entry_count).map_err(|_| bad())?;
    let mut covered_samples = 0u64;
    let mut decode_duration = 0u64;
    for index in 0..entry_count {
        let offset = table_entry_offset(8, index, 8).ok_or_else(bad)?;
        let sample_count = u32be(stts, offset).ok_or_else(bad)?;
        let sample_delta = u32be(
            stts,
            table_entry_offset(offset, 1, 4).ok_or_else(bad)?,
        )
        .ok_or_else(bad)?;
        if sample_count == 0
            || (sample_delta == 0 && !(index + 1 == entry_count && sample_count == 1))
        {
            return Err(bad());
        }
        covered_samples = covered_samples
            .checked_add(sample_count)
            .filter(|&total| total <= expected_samples)
            .ok_or_else(bad)?;
        let run_duration = sample_count.checked_mul(sample_delta).ok_or_else(bad)?;
        decode_duration = decode_duration
            .checked_add(run_duration)
            .ok_or_else(bad)?;
        runs.push((sample_count, sample_delta));
    }
    if covered_samples != expected_samples {
        return Err(bad());
    }
    Ok((runs, decode_duration))
}

pub fn keyframes(r: &mut RangeReader) -> Result<Keyframes> {
    let (moov, quicktime_format) = moov_with_format(r)?;
    if child(&moov, b"mvex").is_some() {
        return Err(Error::Unsupported("fragmented MP4".into()));
    }
    for (kind, trak) in boxes(&moov) {
        if &kind != b"trak" {
            continue;
        }
        let Some(mdia) = child(trak, b"mdia") else {
            continue;
        };
        if child(mdia, b"hdlr").and_then(|h| h.get(8..12)) == Some(b"vide") {
            let number = child(trak, b"tkhd")
                .and_then(|t| u32be(t, if t.first() == Some(&1) { 20 } else { 12 }))
                .unwrap_or(1);
            return video_keyframes(number, mdia, quicktime_format);
        }
    }
    Err(Error::Unsupported("no video track".into()))
}

fn video_keyframes(
    number: u64,
    mdia: &[u8],
    quicktime_format: bool,
) -> Result<Keyframes> {
    let bad = || Error::Malformed("sample tables");
    let mdhd = child(mdia, b"mdhd").ok_or_else(bad)?;
    let (timescale, duration) = parse_media_header(mdhd)?;
    let stbl = path(mdia, &[b"minf", b"stbl"]).ok_or_else(bad)?;
    let (entry_kind, entry) = boxes(
        child(stbl, b"stsd")
            .and_then(|s| s.get(8..))
            .ok_or_else(bad)?,
    )
    .next()
    .ok_or_else(bad)?;
    let codec = match &entry_kind {
        b"avc1" | b"avc3" => "V_MPEG4/ISO/AVC",
        b"hvc1" | b"hev1" | b"dvh1" | b"dvhe" => "V_MPEGH/ISO/HEVC",
        b"av01" => "V_AV1",
        b"vp09" => "V_VP9",
        other => {
            return Err(Error::Unsupported(format!(
                "video codec {}",
                String::from_utf8_lossy(other)
            )));
        }
    };
    let mut track = Track::new(number, TrackKind::Video, codec.to_string());
    let mut video = Video::new(u16be(entry, 24).unwrap_or(0), u16be(entry, 26).unwrap_or(0));
    // A visual sample entry's own fields take 78 bytes.
    for (kind, b) in boxes(entry.get(78..).unwrap_or(&[])) {
        match &kind {
            b"avcC" | b"hvcC" | b"av1C" => track.private = b.to_vec(),
            b"colr" if b.get(..4) == Some(b"nclx") => {
                video.primaries = u16be(b, 4).unwrap_or(2);
                video.transfer = u16be(b, 6).unwrap_or(2);
                video.matrix = u16be(b, 8).unwrap_or(2);
                video.range = if b.get(10).is_some_and(|f| f & 0x80 != 0) {
                    2
                } else {
                    1
                };
            }
            b"pasp" => {
                let (h, v) = (u32be(b, 0).unwrap_or(1), u32be(b, 4).unwrap_or(1));
                if h > 0 && v > 0 {
                    video.display_width = video.width * h / v;
                }
            }
            b"dvcC" | b"dvvC" => video.set_dovi(b),
            _ => {}
        }
    }
    track.video = Some(video);

    let stsz = child(stbl, b"stsz").ok_or_else(bad)?;
    let fixed = u32be(stsz, 4).ok_or_else(bad)?;
    let count = u32be(stsz, 8).ok_or_else(bad)? as usize;
    let size = |s: usize| {
        if fixed != 0 {
            Some(fixed)
        } else {
            table_entry_offset(12, s, 4).and_then(|offset| u32be(stsz, offset))
        }
    };
    let chunks: Vec<u64> = if let Some(stco) = child(stbl, b"stco") {
        (0..u32be(stco, 4).ok_or_else(bad)? as usize)
            .map(|i| table_entry_offset(8, i, 4).and_then(|offset| u32be(stco, offset)))
            .collect::<Option<_>>()
            .ok_or_else(bad)?
    } else {
        let co64 = child(stbl, b"co64").ok_or_else(bad)?;
        (0..u32be(co64, 4).ok_or_else(bad)? as usize)
            .map(|i| table_entry_offset(8, i, 8).and_then(|offset| u64be(co64, offset)))
            .collect::<Option<_>>()
            .ok_or_else(bad)?
    };
    let stsc = child(stbl, b"stsc").ok_or_else(bad)?;
    let runs = parse_sample_to_chunk(stsc, chunks.len(), count as u64)?;
    let stts = child(stbl, b"stts").ok_or_else(bad)?;
    let (stts_runs, decode_duration) = parse_decode_time_table(stts, count)?;
    let ctts = parse_composition_offsets(child(stbl, b"ctts"), quicktime_format, count)?;
    let composition_shift =
        parse_composition_to_decode(child(stbl, b"cslg"))?.unwrap_or(0);
    // Without stss every sample is a sync sample. Entries in stss are one-based.
    let sync = parse_sync_samples(child(stbl, b"stss"), count as u64)?;

    let mut entries = Vec::new();
    let (mut sample, mut run, mut dts) = (0usize, 0usize, 0u64);
    let (mut stts_i, mut stts_left, mut stts_delta) = (0usize, 0u64, 0u64);
    let (mut ctts_i, mut ctts_left, mut ctts_offset) = (0usize, 0u64, 0i64);
    let mut next_sync = 0;
    for (chunk, &offset) in chunks.iter().enumerate() {
        while run + 1 < runs.len() && runs[run + 1].0 <= chunk as u64 + 1 {
            run += 1;
        }
        let mut at = offset;
        for _ in 0..runs.get(run).ok_or_else(bad)?.1 {
            if sample >= count {
                break;
            }
            while stts_left == 0 {
                let (sample_count, sample_delta) =
                    *stts_runs.get(stts_i).ok_or_else(bad)?;
                stts_left = sample_count;
                stts_delta = sample_delta;
                stts_i = stts_i.checked_add(1).ok_or_else(bad)?;
            }
            if let Some(ctts_runs) = &ctts {
                while ctts_left == 0 {
                    let (sample_count, composition_offset) =
                        *ctts_runs.get(ctts_i).ok_or_else(bad)?;
                    ctts_left = sample_count;
                    ctts_offset = composition_offset;
                    ctts_i = ctts_i.checked_add(1).ok_or_else(bad)?;
                }
                ctts_left -= 1;
            }
            let len = size(sample).ok_or_else(bad)?;
            let is_sync = match &sync {
                Some(list) => {
                    let hit = list.get(next_sync) == Some(&(sample as u64));
                    next_sync += hit as usize;
                    hit
                }
                None => true,
            };
            if is_sync {
                entries.push(Keyframe {
                    time_ms: sample_time_ms(dts, ctts_offset, composition_shift, timescale)?,
                    offset: at,
                    relative: None,
                    size: Some(len),
                });
            }
            at = at.checked_add(len).ok_or_else(bad)?;
            dts = dts.checked_add(stts_delta).ok_or_else(bad)?;
            stts_left -= 1;
            sample = sample.checked_add(1).ok_or_else(bad)?;
        }
    }
    entries.sort_by_key(|k| k.time_ms);
    entries.dedup_by_key(|k| k.time_ms);
    if entries.is_empty() {
        return Err(Error::NoIndex);
    }
    Ok(Keyframes {
        container: Container::Mp4,
        track,
        duration_ms: sample_time_ms(duration.unwrap_or(decode_duration), 0, 0, timescale)?,
        scale_ns: 1_000_000,
        entries,
    })
}

#[cfg(test)]
mod tests {
    use super::{
        parse_box_header, parse_sample_to_chunk, parse_sync_samples, sample_time_ms,
        table_entry_offset,
    };
    use crate::error::Error;

    #[test]
    fn parses_a_standard_box_header() {
        let header = [0, 0, 0, 12, b'f', b't', b'y', b'p'];
        assert_eq!(
            parse_box_header(&header).unwrap(),
            (*b"ftyp", 12, 8),
        );
    }

    #[test]
    fn parses_a_valid_extended_box_header() {
        let header = [
            0, 0, 0, 1, b'm', b'o', b'o', b'v',
            0, 0, 0, 0, 0, 0, 0, 16,
        ];
        assert_eq!(
            parse_box_header(&header).unwrap(),
            (*b"moov", 16, 16),
        );
    }

    #[test]
    fn rejects_truncated_and_undersized_box_headers() {
        assert!(matches!(
            parse_box_header(&[0, 0, 0, 8, b'm', b'o', b'o']),
            Err(Error::Malformed("MP4 box header")),
        ));
        assert!(matches!(
            parse_box_header(&[0, 0, 0, 4, b'm', b'o', b'o', b'v']),
            Err(Error::Malformed("MP4 box size")),
        ));
        assert!(matches!(
            parse_box_header(&[0, 0, 0, 1, b'm', b'o', b'o', b'v', 0, 0, 0, 0, 0, 0, 0, 8]),
            Err(Error::Malformed("MP4 box size")),
        ));
    }

    #[test]
    fn rejects_zero_sized_boxes_with_implicit_eof_extent() {
        assert!(matches!(
            parse_box_header(&[0, 0, 0, 0, b'm', b'o', b'o', b'v']),
            Err(Error::Malformed("MP4 box extends to EOF")),
        ));
    }

    fn sync_table(entries: &[u32]) -> Vec<u8> {
        let mut table = vec![0, 0, 0, 0];
        table.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for entry in entries {
            table.extend_from_slice(&entry.to_be_bytes());
        }
        table
    }

    #[test]
    fn parses_one_based_sync_samples_as_zero_based_indices() {
        let table = sync_table(&[1, 3]);
        assert_eq!(
            parse_sync_samples(Some(&table), 3).unwrap(),
            Some(vec![0, 2]),
        );
    }

    #[test]
    fn rejects_zero_duplicate_descending_and_out_of_range_sync_samples() {
        for (entries, sample_count) in [
            (vec![0], 3),
            (vec![1, 1], 3),
            (vec![2, 1], 3),
            (vec![4], 3),
        ] {
            let table = sync_table(&entries);
            assert!(parse_sync_samples(Some(&table), sample_count).is_err());
        }
    }

    #[test]
    fn rejects_truncated_sync_sample_tables_and_unsupported_versions() {
        let mut truncated = sync_table(&[1, 3]);
        truncated.pop();
        assert!(parse_sync_samples(Some(&truncated), 3).is_err());

        let mut unsupported = sync_table(&[1]);
        unsupported[0] = 1;
        assert!(parse_sync_samples(Some(&unsupported), 3).is_err());
    }

    #[test]
    fn absent_sync_table_keeps_the_all_samples_are_sync_default() {
        assert_eq!(parse_sync_samples(None, 12).unwrap(), None);
    }

    #[test]
    fn table_entry_offsets_reject_usize_overflow() {
        assert_eq!(table_entry_offset(8, 2, 4), Some(16));
        assert_eq!(table_entry_offset(usize::MAX, 1, 4), None);
        assert_eq!(table_entry_offset(8, usize::MAX, 4), None);
    }

    #[test]
    fn sample_timestamps_keep_large_decode_times_and_clamp_negative_pts() {
        assert_eq!(sample_time_ms(2_000, -500, 0, 1_000).unwrap(), 1_500);
        assert_eq!(sample_time_ms(100, -200, 0, 1_000).unwrap(), 0);
        assert!(sample_time_ms(u64::MAX, i64::MAX, 0, 1).is_err());
        assert!(sample_time_ms(1, 0, 0, 0).is_err());
    }


    fn stsc_table(entries: &[(u32, u32, u32)]) -> Vec<u8> {
        let mut table = vec![0, 0, 0, 0];
        table.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for (first_chunk, samples_per_chunk, description) in entries {
            table.extend_from_slice(&first_chunk.to_be_bytes());
            table.extend_from_slice(&samples_per_chunk.to_be_bytes());
            table.extend_from_slice(&description.to_be_bytes());
        }
        table
    }

    #[test]
    fn maps_valid_one_based_chunk_runs_to_the_declared_sample_count() {
        let table = stsc_table(&[(1, 2, 1), (3, 1, 1)]);
        assert_eq!(
            parse_sample_to_chunk(&table, 3, 5).unwrap(),
            vec![(1, 2), (3, 1)],
        );
    }

    #[test]
    fn rejects_invalid_stsc_first_chunk_order_and_empty_runs() {
        for entries in [
            vec![(0, 1, 1)],
            vec![(2, 1, 1)],
            vec![(1, 1, 1), (1, 1, 1)],
            vec![(1, 1, 1), (3, 1, 1), (2, 1, 1)],
            vec![(1, 0, 1)],
            vec![(1, 1, 0)],
            vec![(1, 1, 1), (4, 1, 1)],
        ] {
            let table = stsc_table(&entries);
            assert!(parse_sample_to_chunk(&table, 3, 3).is_err());
        }
    }

    #[test]
    fn rejects_stsc_runs_that_do_not_cover_every_declared_sample() {
        let table = stsc_table(&[(1, 2, 1), (3, 1, 1)]);
        assert!(parse_sample_to_chunk(&table, 3, 4).is_err());
        assert!(parse_sample_to_chunk(&table, 3, 6).is_err());
    }

    #[test]
    fn rejects_truncated_stsc_entry_data_and_unsupported_versions() {
        let mut table = stsc_table(&[(1, 2, 1)]);
        table.pop();
        assert!(parse_sample_to_chunk(&table, 1, 2).is_err());

        let mut unsupported = stsc_table(&[(1, 2, 1)]);
        unsupported[0] = 1;
        assert!(parse_sample_to_chunk(&unsupported, 1, 2).is_err());
    }

}

#[cfg(test)]
mod composition_offset_tests {
    use super::{
        is_quicktime_brand, parse_composition_offsets, parse_composition_to_decode,
        sample_time_ms,
    };

    fn ctts_table(version: u8, entries: &[(u32, u32)]) -> Vec<u8> {
        let mut table = vec![version, 0, 0, 0];
        table.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for (sample_count, offset) in entries {
            table.extend_from_slice(&sample_count.to_be_bytes());
            table.extend_from_slice(&offset.to_be_bytes());
        }
        table
    }

    #[test]
    fn iso_version_zero_keeps_the_full_unsigned_offset_range() {
        let table = ctts_table(0, &[(1, 0xF000_0000)]);
        assert_eq!(
            parse_composition_offsets(Some(&table), false, 1).unwrap(),
            Some(vec![(1, 4_026_531_840)]),
        );
    }

    #[test]
    fn iso_version_one_decodes_negative_signed_offsets() {
        let table = ctts_table(1, &[(1, (-100i32) as u32)]);
        assert_eq!(
            parse_composition_offsets(Some(&table), false, 1).unwrap(),
            Some(vec![(1, -100)]),
        );
    }

    #[test]
    fn quicktime_brand_uses_signed_legacy_offsets() {
        let table = ctts_table(0, &[(1, (-100i32) as u32)]);
        assert_eq!(
            parse_composition_offsets(Some(&table), true, 1).unwrap(),
            Some(vec![(1, -100)]),
        );
    }

    #[test]
    fn absent_ctts_preserves_decode_order_timestamps() {
        assert_eq!(parse_composition_offsets(None, false, 3).unwrap(), None);
    }

    #[test]
    fn rejects_unsupported_composition_offset_versions_and_nonzero_flags() {
        let table = ctts_table(2, &[(1, 0)]);
        assert!(parse_composition_offsets(Some(&table), false, 1).is_err());

        let mut flagged = ctts_table(0, &[(1, 0)]);
        flagged[3] = 1;
        assert!(parse_composition_offsets(Some(&flagged), false, 1).is_err());
    }

    #[test]
    fn rejects_zero_length_or_truncated_ctts_entries() {
        let zero_count = ctts_table(0, &[(0, 0)]);
        assert!(parse_composition_offsets(Some(&zero_count), false, 0).is_err());

        let mut truncated = ctts_table(0, &[(1, 0)]);
        truncated.pop();
        assert!(parse_composition_offsets(Some(&truncated), false, 1).is_err());
    }

    #[test]
    fn requires_ctts_runs_to_cover_exactly_the_track_sample_count() {
        let table = ctts_table(0, &[(2, 0)]);
        assert!(parse_composition_offsets(Some(&table), false, 1).is_err());
        assert!(parse_composition_offsets(Some(&table), false, 3).is_err());
    }

    #[test]
    fn detects_the_quicktime_file_type_brand_without_classifying_iso_brands() {
        assert!(is_quicktime_brand(b"qt  "));
        assert!(!is_quicktime_brand(b"isom"));
        assert!(!is_quicktime_brand(b"mp42"));
    }

    fn cslg_table(version: u8, fields: &[i64; 5]) -> Vec<u8> {
        let mut table = vec![version, 0, 0, 0];
        for field in fields {
            if version == 0 {
                table.extend_from_slice(&(*field as i32).to_be_bytes());
            } else {
                table.extend_from_slice(&field.to_be_bytes());
            }
        }
        table
    }

    #[test]
    fn parses_the_published_mpeg_negative_ctso_cslg_and_shifts_only_pts() {
        // Payload fields from 17_negative_ctso.mp4 in the MPEG conformance suite:
        // shift=8, least=-8, greatest=8, composition start=0, end=5000.
        let table = cslg_table(0, &[8, -8, 8, 0, 5_000]);
        let shift = parse_composition_to_decode(Some(&table)).unwrap().unwrap();
        assert_eq!(shift, 8);
        assert_eq!(sample_time_ms(0, -8, shift, 1_000).unwrap(), 0);
        assert_eq!(sample_time_ms(1, -8, shift, 1_000).unwrap(), 1);
    }

    #[test]
    fn parses_version_one_cslg_with_signed_64_bit_fields() {
        let table = cslg_table(1, &[i64::MAX, i64::MIN, 8, i64::MIN, i64::MAX]);
        assert_eq!(
            parse_composition_to_decode(Some(&table)).unwrap(),
            Some(i64::MAX),
        );
    }

    #[test]
    fn rejects_malformed_cslg_versions_flags_lengths_and_bounds() {
        let mut unsupported = cslg_table(0, &[8, -8, 8, 0, 5_000]);
        unsupported[0] = 2;
        assert!(parse_composition_to_decode(Some(&unsupported)).is_err());

        let mut flagged = cslg_table(0, &[8, -8, 8, 0, 5_000]);
        flagged[3] = 1;
        assert!(parse_composition_to_decode(Some(&flagged)).is_err());

        let mut truncated = cslg_table(0, &[8, -8, 8, 0, 5_000]);
        truncated.pop();
        assert!(parse_composition_to_decode(Some(&truncated)).is_err());

        assert!(parse_composition_to_decode(Some(&cslg_table(0, &[8, 2, 1, 0, 5_000]))).is_err());
        assert!(parse_composition_to_decode(Some(&cslg_table(0, &[8, -8, 8, 10, 9]))).is_err());
    }

    #[test]
    fn absent_cslg_does_not_shift_the_presentation_timeline() {
        assert_eq!(parse_composition_to_decode(None).unwrap(), None);
        assert_eq!(sample_time_ms(1_000, -100, 0, 1_000).unwrap(), 900);
    }
}

#[cfg(test)]
mod decode_time_table_tests {
    use super::parse_decode_time_table;

    fn stts_table(version: u8, entries: &[(u32, u32)]) -> Vec<u8> {
        let mut table = vec![version, 0, 0, 0];
        table.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        for (sample_count, sample_delta) in entries {
            table.extend_from_slice(&sample_count.to_be_bytes());
            table.extend_from_slice(&sample_delta.to_be_bytes());
        }
        table
    }

    #[test]
    fn validates_complete_stts_runs_and_sums_decode_duration() {
        let table = stts_table(0, &[(2, 100), (1, 0)]);
        assert_eq!(
            parse_decode_time_table(&table, 3).unwrap(),
            (vec![(2, 100), (1, 0)], 200),
        );
    }

    #[test]
    fn rejects_stts_version_flags_and_inexact_box_lengths() {
        let mut unsupported = stts_table(0, &[(1, 100)]);
        unsupported[0] = 1;
        assert!(parse_decode_time_table(&unsupported, 1).is_err());

        let mut flagged = stts_table(0, &[(1, 100)]);
        flagged[3] = 1;
        assert!(parse_decode_time_table(&flagged, 1).is_err());

        let mut truncated = stts_table(0, &[(1, 100)]);
        truncated.pop();
        assert!(parse_decode_time_table(&truncated, 1).is_err());

        let mut trailing = stts_table(0, &[(1, 100)]);
        trailing.push(0);
        assert!(parse_decode_time_table(&trailing, 1).is_err());
    }

    #[test]
    fn rejects_zero_counts_and_zero_deltas_before_the_final_sample() {
        for table in [
            stts_table(0, &[(0, 100)]),
            stts_table(0, &[(2, 0)]),
            stts_table(0, &[(1, 0), (1, 100)]),
        ] {
            assert!(parse_decode_time_table(&table, 2).is_err());
        }
    }

    #[test]
    fn rejects_stts_runs_that_do_not_cover_the_sample_table() {
        let table = stts_table(0, &[(1, 100)]);
        assert!(parse_decode_time_table(&table, 0).is_err());
        assert!(parse_decode_time_table(&table, 2).is_err());
    }

    #[test]
    fn accepts_unknown_last_sample_duration_as_zero() {
        let table = stts_table(0, &[(1, 100), (1, 0)]);
        assert_eq!(
            parse_decode_time_table(&table, 2).unwrap(),
            (vec![(1, 100), (1, 0)], 100),
        );
    }
}

#[cfg(test)]
mod media_header_tests {
    use super::parse_media_header;

    fn mdhd_v0(timescale: u32, duration: u32) -> Vec<u8> {
        let mut box_body = vec![0, 0, 0, 0];
        box_body.extend_from_slice(&0u32.to_be_bytes());
        box_body.extend_from_slice(&0u32.to_be_bytes());
        box_body.extend_from_slice(&timescale.to_be_bytes());
        box_body.extend_from_slice(&duration.to_be_bytes());
        box_body.extend_from_slice(&[0, 0, 0, 0]);
        box_body
    }

    fn mdhd_v1(timescale: u32, duration: u64) -> Vec<u8> {
        let mut box_body = vec![1, 0, 0, 0];
        box_body.extend_from_slice(&0u64.to_be_bytes());
        box_body.extend_from_slice(&0u64.to_be_bytes());
        box_body.extend_from_slice(&timescale.to_be_bytes());
        box_body.extend_from_slice(&duration.to_be_bytes());
        box_body.extend_from_slice(&[0, 0, 0, 0]);
        box_body
    }

    #[test]
    fn treats_all_ones_media_duration_as_unknown_in_both_versions() {
        assert_eq!(parse_media_header(&mdhd_v0(1_000, u32::MAX)).unwrap(), (1_000, None));
        assert_eq!(parse_media_header(&mdhd_v1(48_000, u64::MAX)).unwrap(), (48_000, None));
    }

    #[test]
    fn preserves_known_media_durations_in_both_versions() {
        assert_eq!(parse_media_header(&mdhd_v0(1_000, 12_345)).unwrap(), (1_000, Some(12_345)));
        assert_eq!(parse_media_header(&mdhd_v1(48_000, 987_654)).unwrap(), (48_000, Some(987_654)));
    }

    #[test]
    fn rejects_unsupported_versions_flags_invalid_timescales_and_lengths() {
        let mut unsupported = mdhd_v0(1_000, 12_345);
        unsupported[0] = 2;
        assert!(parse_media_header(&unsupported).is_err());

        let mut flagged = mdhd_v0(1_000, 12_345);
        flagged[3] = 1;
        assert!(parse_media_header(&flagged).is_err());

        assert!(parse_media_header(&mdhd_v0(0, 12_345)).is_err());

        let mut truncated = mdhd_v1(48_000, 987_654);
        truncated.pop();
        assert!(parse_media_header(&truncated).is_err());
    }
}
