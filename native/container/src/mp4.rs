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
