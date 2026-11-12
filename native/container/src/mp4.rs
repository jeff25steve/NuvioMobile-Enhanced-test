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

fn sample_time_ms(dts: u64, composition_offset: i64, timescale: u64) -> Result<u64> {
    let bad = || Error::Malformed("sample timestamps");
    if timescale == 0 {
        return Err(bad());
    }
    let presentation_time = (i128::from(dts) + i128::from(composition_offset)).max(0) as u128;
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

/// The moov box's body, before or after the media data.
pub fn moov(r: &mut RangeReader) -> Result<Vec<u8>> {
    let mut pos = 0u64;
    loop {
        let head = r.read(pos, 16)?;
        let (kind, size, header_size) = parse_box_header(&head)?;
        if pos == 0 && kind != *b"ftyp" {
            return Err(Error::Unsupported("not MP4".into()));
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
            return Ok(body);
        }
        if kind == *b"moof" {
            return Err(Error::Unsupported("fragmented MP4".into()));
        }
        pos = pos.checked_add(size).ok_or(Error::Malformed("MP4 box offset"))?;
    }
}

pub fn keyframes(r: &mut RangeReader) -> Result<Keyframes> {
    let moov = moov(r)?;
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
            return video_keyframes(number, mdia);
        }
    }
    Err(Error::Unsupported("no video track".into()))
}

fn video_keyframes(number: u64, mdia: &[u8]) -> Result<Keyframes> {
    let bad = || Error::Malformed("sample tables");
    let mdhd = child(mdia, b"mdhd").ok_or_else(bad)?;
    let (timescale, duration) = if mdhd.first() == Some(&1) {
        (u32be(mdhd, 20), u64be(mdhd, 24))
    } else {
        (u32be(mdhd, 12), u32be(mdhd, 16))
    };
    let timescale = timescale.filter(|&t| t > 0).ok_or_else(bad)?;
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
    let ctts = child(stbl, b"ctts");
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
                stts_left = u32be(
                    stts,
                    table_entry_offset(8, stts_i, 8).ok_or_else(bad)?,
                )
                .ok_or_else(bad)?;
                stts_delta = u32be(
                    stts,
                    table_entry_offset(12, stts_i, 8).ok_or_else(bad)?,
                )
                .ok_or_else(bad)?;
                stts_i = stts_i.checked_add(1).ok_or_else(bad)?;
            }
            if let Some(ctts) = ctts {
                while ctts_left == 0 {
                    ctts_left = u32be(
                        ctts,
                        table_entry_offset(8, ctts_i, 8).ok_or_else(bad)?,
                    )
                    .ok_or_else(bad)?;
                    ctts_offset = u32be(
                        ctts,
                        table_entry_offset(12, ctts_i, 8).ok_or_else(bad)?,
                    )
                    .ok_or_else(bad)? as u32 as i32 as i64;
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
                    time_ms: sample_time_ms(dts, ctts_offset, timescale)?,
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
        duration_ms: sample_time_ms(duration.unwrap_or(dts), 0, timescale)?,
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
        assert_eq!(sample_time_ms(2_000, -500, 1_000).unwrap(), 1_500);
        assert_eq!(sample_time_ms(100, -200, 1_000).unwrap(), 0);
        assert!(sample_time_ms(u64::MAX, i64::MAX, 1).is_err());
        assert!(sample_time_ms(1, 0, 0).is_err());
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
