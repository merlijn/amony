import {useLocalStorage} from "usehooks-ts"
import * as v from "valibot"
import {Constants} from "./Constants"

export const themeSettings = ['system', 'light', 'dark'] as const
export const gridAspectRatios = ['2/1', '16/9', '3/2', '5/4', '1/1', 'golden'] as const
export const gridOrientations = ['landscape', 'portrait'] as const
export const listViewDefaultActions = ['view', 'show-info'] as const

export type ThemeSetting = typeof themeSettings[number]
export type GridAspectRatio = typeof gridAspectRatios[number]
export type GridOrientation = typeof gridOrientations[number]
export type ListViewDefaultAction = typeof listViewDefaultActions[number]

export type GridPrefs = {
  /** Target width (px) of a single grid cell; the column count is derived from the available width. */
  cellWidth: number
  aspectRatio: GridAspectRatio
  orientation: GridOrientation
  showTitles: boolean
  showDuration: boolean
  showDates: boolean
  showResolution: boolean
}

export type ListViewPrefs = {
  defaultAction: ListViewDefaultAction
}

export type Prefs = {
  showSidebar: boolean
  theme: ThemeSetting
  grid: GridPrefs
  listView: ListViewPrefs
}

const defaults = Constants.defaultPreferences

// Every field falls back to its default, so a partially written or outdated
// config is repaired instead of rejected. The group and root schemas fall back
// too, to tolerate configs that predate a group entirely.
const GridPrefsSchema = v.object({
  cellWidth:      v.fallback(v.pipe(v.number(), v.finite()), defaults.grid.cellWidth),
  aspectRatio:    v.fallback(v.picklist(gridAspectRatios), defaults.grid.aspectRatio),
  orientation:    v.fallback(v.picklist(gridOrientations), defaults.grid.orientation),
  showTitles:     v.fallback(v.boolean(), defaults.grid.showTitles),
  showDuration:   v.fallback(v.boolean(), defaults.grid.showDuration),
  showDates:      v.fallback(v.boolean(), defaults.grid.showDates),
  showResolution: v.fallback(v.boolean(), defaults.grid.showResolution),
})

const ListViewPrefsSchema = v.object({
  defaultAction: v.fallback(v.picklist(listViewDefaultActions), defaults.listView.defaultAction),
})

const PrefsSchema = v.object({
  showSidebar: v.fallback(v.boolean(), defaults.showSidebar),
  theme:       v.fallback(v.picklist(themeSettings), defaults.theme),
  grid:        v.fallback(GridPrefsSchema, defaults.grid),
  listView:    v.fallback(ListViewPrefsSchema, defaults.listView),
})

export const deserializePrefs = (raw: string): Prefs => {
  try {
    const result = v.safeParse(PrefsSchema, JSON.parse(raw))
    return result.success ? result.output : Constants.defaultPreferences
  } catch {
    return Constants.defaultPreferences
  }
}

export const usePrefs = (): [Prefs, (values: Partial<Prefs>) => void] => {
  const [prefs, setPrefs] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences, {deserializer: deserializePrefs})
  const updatePrefs = (values: Partial<Prefs>) => setPrefs(previous => ({...previous, ...values}))
  return [prefs, updatePrefs]
}
