export type ResourceSelection = {
  query?: string
  playlist?: string
  tag?: string
  untagged?: boolean
  duration?: [number?, number?]
  uploadAge?: [number?, number?]
  minimumQuality: number
  sort: Sort
}

export type MediaView = 'grid' | 'list'

export type Resolution = {
  value: number,
  label: string
}

export type SortField = 'title' | 'date_added' | 'duration' | 'size'
export type SortDirection = 'asc' | 'desc'

export type RegularSort = {
  field: SortField
  direction: SortDirection
}

export type RandomSort = {
  field: 'random'
  seed: number
}

export type Sort = RegularSort | RandomSort
export type ThemeSetting = 'light' | 'dark' | 'system'
export type GridAspectRatio = '2/1' | '16/9' | '3/2' | '5/4' | '1/1'
export type GridOrientation = 'landscape' | 'portrait'

export type Prefs = {
  showSidebar: boolean
  showTitles: boolean
  showDuration: boolean
  showDates: boolean
  showResolution: boolean
  gallery_columns: number
  theme: ThemeSetting
  gridAspectRatio: GridAspectRatio
  gridOrientation: GridOrientation
}

export type SessionInfo = {
  isLoggedIn: () => boolean
  isAdmin: () => boolean
}
