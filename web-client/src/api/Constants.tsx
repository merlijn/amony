import {Prefs, Resolution} from "./Model";

const resolutions: Array<Resolution> =
   [{ value: 0,    label: "All"},
    { value: 720,  label: "HD" },
    { value: 1080, label: "FHD"},
    { value: 2160, label: "4K"}]

const sortingOps = [
  { value: "title",      label: "Title" },
  { value: "date_added", label: "Date added" },
  { value: "duration",   label: "Duration" }];

const defaultPrefs: Prefs = {
  showTitles:      false,
  showDuration:    true,
  showMenu:        true,
  sortField:       "title",
  sortDirection:   "asc",
  gallery_columns: 0,
  minRes:          0
}

export const Constants = {

  imgAlt: "<image here>",

  resolutions: resolutions,

  sortOptions: sortingOps,

  defaultPreferences: defaultPrefs,

  gridSize: 400,
}

