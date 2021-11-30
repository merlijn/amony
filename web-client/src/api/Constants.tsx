import {Prefs, Resolution} from "./Model";

const resolutions: Array<Resolution> =
   [{ value: 0,    label: "All"},
    { value: 720,  label: "HD" },
    { value: 1080, label: "FHD"},
    { value: 2160, label: "4K"}]

const sortingOptions = [
  { value: { field: "title", direction: 'asc' }, label: "Alphabetically" },
  { value: { field: "date_added", direction: 'desc' }, label: "By date added" },
  { value: { field: "duration", direction: 'asc' }, label: "By duration" }];

const defaultPrefs: Prefs = {
  showSidebar:     false,
  showTitles:      true,
  showDuration:    true,
  showMenu:        false,
  showDates:       false,
  sort:            { field: 'date_added', direction: 'desc' },
  gallery_columns: 'auto',
  videoQuality:    0
}

export const Constants = {

  imgAlt: "<image here>",

  resolutions: resolutions,

  sortOptions: sortingOptions,

  defaultPreferences: defaultPrefs,

  gridSize: 400,
}

