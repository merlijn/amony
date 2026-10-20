import {Prefs} from "./Prefs";
import {ContentTypeGroup, Resolution, SessionInfo, Sort, SortDirection, RegularSort, ThresholdFilter} from "./Model";
import { useUrlParam } from "./ReactUtils";
import React from "react";

const resolutions: Array<Resolution> =
   [{ value: 0,    label: "Any", qualityLabel: "SD"},
    { value: 720,  label: "HD" },
    { value: 1080, label: "FHD"},
    { value: 2160, label: "4K"}]

const sortingOptions: Array<{value: RegularSort, label: string}> = [
  { value: { field: "date_added", direction: "desc" }, label: "Date" },
  { value: { field: "title", direction: "asc" },       label: "Title" },
  { value: { field: "duration", direction: "asc" },    label: "Duration" }];

export const generateRandomSeed = (): number => {
  return Math.floor(10000 + Math.random() * 90000);
}

export const parseSortParam = (param: string): Sort => {

  if (param.startsWith("random-")) {
    const seedStr = param.substring(7)
    const seed = parseInt(seedStr, 10)
    if (!isNaN(seed) && seedStr.length === 5) {
      return { field: "random", seed }
    }
  }

  const split = param.split(";")
  const field = split[0] as 'title' | 'date_added' | 'duration' | 'size'
  const dir   = split[1] as SortDirection
  return { field : field, direction: dir }
}

export const useSortParam = (): [Sort, (v: Sort) => void] => {

  const [param, setParam] = useUrlParam("s", "date_added;desc");
  const sort: Sort = parseSortParam(param)
  const updateParam = (s: Sort) => { 
    setParam(s.field === "random" 
      ? `random-${s.seed.toString().padStart(5, '0')}` 
      : `${s.field};${s.direction}`) 
  }

  return [sort, updateParam];
}

export const parseDurationParam = (s: string): [number?, number?] => {

  if (s === "-") 
    return [undefined, undefined]
  if (s.endsWith("-"))
    return [parseInt(s.substring(0, s.length-1)), undefined]
  else if (s.startsWith("-"))
    return [undefined, parseInt(s.substring(1))]
  else {
    const parts = s.split("-", 2)
    return [parseInt(parts[0]), parseInt(parts[1])]
  }
}

export const rangeAsParameter = (v: [number?, number?]) => {
  return `${v[0] !== undefined ? v[0] : ""}-${v[1] !== undefined ? v[1] : ""}`;
}

export const parseResolutionParam = (param: string): ThresholdFilter | undefined => {
  const [value, direction] = param.split("-", 2)
  const resolved = parseInt(value, 10)

  if (!resolved)
    return undefined

  return { value: resolved, direction: direction === "max" ? "max" : "min" }
}

export const resolutionAsParameter = (filter: ThresholdFilter | undefined) =>
  filter ? `${filter.value}-${filter.direction}` : "0"

export const parseThresholdRangeParam = (param: string): ThresholdFilter | undefined => {
  const [min, max] = parseDurationParam(param)

  if (min !== undefined)
    return { value: min, direction: "min" }
  if (max !== undefined)
    return { value: max, direction: "max" }

  return undefined
}

export const thresholdRangeAsParameter = (filter: ThresholdFilter | undefined) =>
  rangeAsParameter(filter
    ? [filter.direction === "min" ? filter.value : undefined, filter.direction === "max" ? filter.value : undefined]
    : [undefined, undefined])

const durationOptions: Array<{value: number, label: string}> = [
  { value: 0,    label: "Any" },
  { value: 60,   label: "1 minute" },
  { value: 600,  label: "10 minutes" },
  { value: 1800, label: "30 minutes" },
  { value: 3600, label: "60 minutes" }];

const uploadOptions: Array<{value: number, label: string}> = [
  { value: 0,         label: "Any" },
  { value: 3600,      label: "1 hour" },
  { value: 24 * 3600, label: "24 hours" },
  { value: 7 * 24 * 3600,  label: "7 days" },
  { value: 30 * 24 * 3600, label: "30 days" },
  { value: 365 * 24 * 3600, label: "1 year" }];

const contentTypeOptions: Array<{value: ContentTypeGroup, label: string}> = [
  { value: "image", label: "Images" },
  { value: "video", label: "Videos" },
  { value: "other", label: "Other" }];

export const parseContentTypeParam = (param: string): ContentTypeGroup[] =>
  param.split(",").filter((value): value is ContentTypeGroup => contentTypeOptions.some(option => option.value === value))

const defaultPrefs: Prefs = {
  showSidebar: false,
  theme:       "system",
  grid: {
    cellWidth:      320,
    aspectRatio:    "golden",
    orientation:    "landscape",
    showTitles:     true,
    showDuration:   true,
    showDates:      true,
    showResolution: true,
  },
  listView: {
    defaultAction: "view",
  },
}

const anonymousSession: SessionInfo = {
  userId: "anonymous",
  roles: [],
  isLoggedIn: () => false,
  isAdmin: () => false,
}

export const SessionContext = React.createContext<SessionInfo>(anonymousSession);

export const Constants = {

  anonymousSession: anonymousSession,

  imgAlt: "<image here>",

  preferenceKey: "prefs-v1",

  durationOptions: durationOptions,

  uploadOptions: uploadOptions,

  contentTypeOptions: contentTypeOptions,

  resolutions: resolutions,

  sortOptions: sortingOptions,

  defaultPreferences: defaultPrefs,

  minGridCellWidth: 96,

  maxGridCellWidth: 768,

  sideBarWidth: 150,
}

