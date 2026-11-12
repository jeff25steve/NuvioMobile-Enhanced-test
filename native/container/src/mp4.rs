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

/// Parse `stsz` as a complete FullBox and return (fixed_size, sample_count).
/// Variable-size tables must declare exact sample coverage and body length.
fn parse_sample_sizes(stsz: &[u8]) -> Result<(u32, usize)> {
    let bad = || Error::Malformed("sample size table");
    if stsz.len() < 12 || stsz[0] != 0 || stsz[1..4] != [0, 0, 0] {
        return Err(bad());
    }
    let fixed = u32::try_from(u32be(stsz, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    let count = usize::try_from(u32be(stsz, 8).ok_or_else(bad)?).map_err(|_| bad())?;
    if count == 0 {
        return Err(bad());
    }
    if fixed != 0 {
        if stsz.len() != 12 {
            return Err(bad());
        }
        return Ok((fixed, count));
    }
    let required_len = count
        .checked_mul(4)
        .and_then(|bytes| bytes.checked_add(12))
        .ok_or_else(bad)?;
    if stsz.len() != required_len {
        return Err(bad());
    }
    for index in 0..count {
        let offset = table_entry_offset(12, index, 4).ok_or_else(bad)?;
        let size = u32be(stsz, offset).ok_or_else(bad)?;
        if size == 0 {
            return Err(bad());
        }
    }
    Ok((0, count))
}

/// Parse `stco` (32-bit) or `co64` (64-bit) chunk offset tables with exact body length.
fn parse_chunk_offsets(table: &[u8], entry_width: usize) -> Result<Vec<u64>> {
    let bad = || Error::Malformed("chunk offset table");
    if !(entry_width == 4 || entry_width == 8) {
        return Err(bad());
    }
    if table.len() < 8 || table[0] != 0 || table[1..4] != [0, 0, 0] {
        return Err(bad());
    }
    let entry_count = usize::try_from(u32be(table, 4).ok_or_else(bad)?).map_err(|_| bad())?;
    if entry_count == 0 {
        return Err(bad());
    }
    let required_len = entry_count
        .checked_mul(entry_width)
        .and_then(|bytes| bytes.checked_add(8))
        .ok_or_else(bad)?;
    if table.len() != required_len {
        return Err(bad());
    }
    let mut chunks = Vec::new();
    chunks.try_reserve_exact(entry_count).map_err(|_| bad())?;
    for index in 0..entry_count {
        let offset = table_entry_offset(8, index, entry_width).ok_or_else(bad)?;
        let value = if entry_width == 4 {
            u32be(table, offset)
        } else {
            u64be(table, offset)
        }
        .ok_or_else(bad)?;
        chunks.push(value);
    }
    Ok(chunks)
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
