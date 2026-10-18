import {useLocalStorage} from "usehooks-ts"
import {Constants} from "./Constants"

export const themeSettings = ['system', 'light', 'dark'] as const
export type ThemeSetting = typeof themeSettings[number]

export const gridAspectRatios = ['2/1', '16/9', '3/2', '5/4', '1/1', 'golden'] as const
export type GridAspectRatio = typeof gridAspectRatios[number]

export const gridOrientations = ['landscape', 'portrait'] as const
export type GridOrientation = typeof gridOrientations[number]

export const listViewDefaultActions = ['view', 'show-info'] as const
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

type RecordValue = Record<string, unknown>

const asRecord = (value: unknown): RecordValue =>
  typeof value === "object" && value !== null && !Array.isArray(value) ? value as RecordValue : {}

const asBoolean = (value: unknown, fallback: boolean): boolean =>
  typeof value === "boolean" ? value : fallback

const asNumber = (value: unknown, fallback: number): number =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback

const asOption = <T extends string>(value: unknown, options: readonly T[], fallback: T): T =>
  options.includes(value as T) ? value as T : fallback

const parseGridPrefs = (value: unknown): GridPrefs => {
  const stored   = asRecord(value)
  const defaults = Constants.defaultPreferences.grid

  return {
    cellWidth:      asNumber(stored.cellWidth, defaults.cellWidth),
    aspectRatio:    asOption(stored.aspectRatio, gridAspectRatios, defaults.aspectRatio),
    orientation:    asOption(stored.orientation, gridOrientations, defaults.orientation),
    showTitles:     asBoolean(stored.showTitles, defaults.showTitles),
    showDuration:   asBoolean(stored.showDuration, defaults.showDuration),
    showDates:      asBoolean(stored.showDates, defaults.showDates),
    showResolution: asBoolean(stored.showResolution, defaults.showResolution),
  }
}

const parseListViewPrefs = (value: unknown): ListViewPrefs => {
  const defaults = Constants.defaultPreferences.listView

  return {
    defaultAction: asOption(asRecord(value).defaultAction, listViewDefaultActions, defaults.defaultAction),
  }
}

export const deserializePrefs = (raw: string): Prefs => {
  try {
    const value = asRecord(JSON.parse(raw))

    return {
      showSidebar: asBoolean(value.showSidebar, Constants.defaultPreferences.showSidebar),
      theme:       asOption(value.theme, themeSettings, Constants.defaultPreferences.theme),
      grid:        parseGridPrefs(value.grid),
      listView:    parseListViewPrefs(value.listView),
    }
  } catch {
    return Constants.defaultPreferences
  }
}

export const usePrefs = (): [Prefs, (values: Partial<Prefs>) => void] => {
  const [prefs, setPrefs] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences, {deserializer: deserializePrefs})
  const updatePrefs = (values: Partial<Prefs>) => setPrefs(previous => ({...previous, ...values}))
  return [prefs, updatePrefs]
}
