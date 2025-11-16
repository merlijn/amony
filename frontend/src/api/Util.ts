import { CSSProperties } from "react";
import {Constants, durationAsParam} from "./Constants";
import {ResourceSelection} from "./Model";
import {FindResourcesParams} from "./generated";

export function buildUrl(path: string, urlParams: Map<string, string> | undefined) {

  let paramPath = ""

  if (urlParams)
    urlParams.forEach((val, key) => { paramPath += `&${key}=${val}` })

  const result = paramPath ? `${path}?${paramPath.substring(1)}` : path

  return result
}

export const titleFromPath = (path: string) => {
  const fileNameIncludingExtension = path.split('/').pop() || "Untitled"
  return fileNameIncludingExtension.split('.').slice(0, -1).join('.')
}

const canPlayTypeCache: Record<string, boolean> = {};

export function canBrowserPlayType(contentType: string): boolean {
  if (canPlayTypeCache.hasOwnProperty(contentType)) {
    return canPlayTypeCache[contentType];
  }
  let result = false;
  if (contentType.startsWith('video/')) {
    const video = document.createElement('video');
    result = video.canPlayType(contentType) !== '';
  } else if (contentType.startsWith('audio/')) {
    const audio = document.createElement('audio');
    result = audio.canPlayType(contentType) !== '';
  } else if (contentType.startsWith('image/')) {
    result = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/svg+xml'].includes(contentType);
  }
  canPlayTypeCache[contentType] = result;
  return result;
}

export function copyParams(params: URLSearchParams) {

  const copy = new Map<string, string>()
  params.forEach((val, key) => {
    copy.set(key, val)
  })

  return copy
}

// https://stackoverflow.com/questions/15900485/
export function formatByteSize(bytes: number, decimals: number = 2) {
  if (bytes === 0) return '0 Bytes';

  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];

  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

export function labelForResolution(height: number) {
  const matches = Constants.resolutions.filter((e) => height >= e.value)

  return matches[matches.length-1].label
}

export function durationInMillisToString(duration: number) {

  const secondsInMillis = 1000;
  const minutesInMilis = 1000 * 60;
  const hoursInMillis = minutesInMilis * 60;

  const hours = Math.trunc(duration / hoursInMillis)
  const minutes = Math.trunc(duration % hoursInMillis / minutesInMilis)
  const seconds = Math.trunc(duration % minutesInMilis / secondsInMillis)

  let durationStr = ""

  if (hours > 0) {
    durationStr += `${hours}:`
    if (minutes < 10)
      durationStr += '0'
  }

  durationStr += `${minutes}:`

  if (seconds < 10)
    durationStr += '0'

  durationStr += `${seconds}`

  return durationStr
}

export function dateMillisToString(millis: number) {

  const date = new Date(millis)
  const days = zeroPad(date.getUTCDate(), 2)
  const month = zeroPad(date.getMonth() + 1, 2)
  const year = date.getFullYear()

  return `${year}-${month}-${days}`;
}

export function zeroPad(n: number, d: number) {

  let zeros = "";
  let limit = Math.max(0, d - 1 - (n ? Math.floor(Math.log10(n)) : 0))

  for(let i = 0; i < limit; i++)
    zeros += "0"

  return zeros + n.toString()
}

export function resourceSelectionToParams(selection: ResourceSelection, offset: number, n: number): FindResourcesParams {
  const duration= selection.duration

  return {
    offset: offset,
    n: n,
    q: selection.query,
    tag: selection.untagged ? undefined : selection.tag,
    untagged: selection.untagged || undefined,
    sort_field: selection.sort.field,
    sort_dir: selection.sort.direction,
    min_res: selection.minimumQuality,
    d: duration ? durationAsParam([duration[0] ? duration[0] * 1000 : duration[0], duration[1] ? duration[1] * 1000 : duration[1]]) : undefined
  }
}

export function boundedRatioBox(maxWidth: string, maxHeight: string, ratio: number): CSSProperties {
  return {
    width: `min(${maxWidth}, ${maxHeight} * ${ratio})`,
    height: `min(${maxHeight}, ${maxWidth} * 1 / ${ratio})`
  }
}

export const calculateColumns = () => {
  const c = Math.max(1, Math.round(window.innerWidth / Constants.gridSize));
  return c;
}
