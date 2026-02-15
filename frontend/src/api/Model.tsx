export type ResourceSelection = {
  query?: string
  playlist?: string
  tag?: string
  untagged?: boolean
  duration?: [number?, number?]
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
export type Columns = 'auto' | number
export type ThemeSetting = 'light' | 'dark' | 'system'

export type Prefs = {
  showSidebar: boolean
  showTitles: boolean
  showDuration: boolean
  showDates: boolean
  showResolution: boolean
  gallery_columns: Columns
  theme: ThemeSetting
}

export type SessionInfo = {
  isLoggedIn: () => boolean
  isAdmin: () => boolean
}
