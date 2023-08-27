import {Prefs, Resolution, Sort, SortDirection} from "./Model";
import { useUrlParam } from "./ReactUtils";

const resolutions: Array<Resolution> =
   [{ value: 0,    label: "SD"},
    { value: 720,  label: "HD" },
    { value: 1080, label: "FHD"},
    { value: 2160, label: "4K"}]

const sortingOptions: Array<{value: Sort, label: string}> = [
  { value: { field: "date_added", direction: "desc" }, label: "By date" },
  { value: { field: "title", direction: "asc" },       label: "By title" },
  { value: { field: "duration", direction: "asc" },    label: "By duration" }];

export const parseSortParam = (param: string): Sort => {

  const split = param.split(";")
  const field = split[0]
  const dir   = split[1] as SortDirection
  return { field : field, direction: dir }
}

export const useSortParam = (): [Sort, (v: Sort) => void] => {

  const [param, setParam] = useUrlParam("s", "date_added;desc");
  const sort: Sort = parseSortParam(param)
  const updateParam = (s: Sort) => { setParam(`${s.field};${s.direction}`) }

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

export const durationAsParam = (v: [number?, number?]) => {
  return `${v[0] !== undefined ? v[0] : ""}-${v[1] !== undefined ? v[1] : ""}`;
}

const durationOptions: Array<{value: [number?, number?], label: string}> = [
  { value: [undefined, undefined], label: "any" },
  { value: [undefined, 60], label: "< 1 minute" },
  { value: [60, 600], label: "1-10 minutes" },
  { value: [600, 1800], label: "10-30 minutes" },
  { value: [1800, 3600], label: "30-60 minutes" },
  { value: [3600, undefined], label: "> 60 minutes" }];

const uploadOptions: Array<{value: [number?, number?], label: string}> = [
  { value: [undefined, undefined], label: "any" },
  { value: [undefined, 24], label: "last 24 hours" },
  { value: [undefined, 7*24], label: "last 7 days" },
  { value: [undefined, 30*24], label: "last 30 days" },
  { value: [undefined, 365*24], label: "last year" },
  { value: [365*24, undefined], label: "> 1 year ago" }];

const defaultPrefs: Prefs = {
  showSidebar:     false,
  showTitles:      true,
  showDuration:    true,
  showDates:       false,
  gallery_columns: "auto",
}

export const Constants = {

  imgAlt: "<image here>",

  durationOptions: durationOptions,

  uploadOptions: uploadOptions,

  resolutions: resolutions,

  sortOptions: sortingOptions,

  defaultPreferences: defaultPrefs,

  gridSize: 400,
}

