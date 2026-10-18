export type ResourceSelection = {
  query?: string
  playlist?: string
  tag?: string
  untagged?: boolean
  streamable?: boolean
  duration?: [number?, number?]
  uploadAge?: [number?, number?]
  minimumQuality: number
  sort: Sort
}

export type MediaView = 'grid' | 'list'

export type Resolution = {
  value: number,
  label: string,
  qualityLabel?: string
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

export type SessionInfo = {
  userId: string
  roles: string[]
  isLoggedIn: () => boolean
  isAdmin: () => boolean
}
