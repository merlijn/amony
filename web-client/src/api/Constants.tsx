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

const durationOptions = [
  { value: [0, 60], label: "< 1 minute" },
  { value: [60, 300], label: "1-10 minutes" },
  { value: [300, -1], label: "10-30 minutes" },
  { value: [300, -1], label: "30-60 minutes" },
  { value: [300, -1], label: "> 60 minutes" }];

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

  durationOptions: durationOptions,

  resolutions: resolutions,

  sortOptions: sortingOptions,

  defaultPreferences: defaultPrefs,

  gridSize: 400,
}

