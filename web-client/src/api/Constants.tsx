import {Prefs, Resolution, Sort} from "./Model";

const resolutions: Array<Resolution> =
   [{ value: 0,    label: "All"},
    { value: 720,  label: "HD" },
    { value: 1080, label: "FHD"},
    { value: 2160, label: "4K"}]

const sortingOptions = [
  { value: { field: "date_added", direction: "desc" }, label: "By date" },
  { value: { field: "title", direction: "asc" },       label: "By title" },
  { value: { field: "duration", direction: "asc" },    label: "By duration" }];

export const parseSortParam = (s: string): Sort => {
  switch (s) {
    case "duration":
      return { field: "duration", direction: "asc" };
    case "title":
      return { field: "title", direction: "asc" };
    default: 
      return { field: "date_added", direction: "desc" };
  }
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

const defaultPrefs: Prefs = {
  showSidebar:     false,
  showTitles:      true,
  showDuration:    true,
  showMenu:        false,
  showDates:       false,
  gallery_columns: "auto",
}

export const Constants = {

  imgAlt: "<image here>",

  durationOptions: durationOptions,

  resolutions: resolutions,

  sortOptions: sortingOptions,

  defaultPreferences: defaultPrefs,

  gridSize: 400,
}

