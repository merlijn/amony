export type ResourceUrls = {
  originalResourceUrl: string,
  thumbnailUrl: string,
  previewThumbnailsUrl?: string,
}

export type ResourceMeta = {
  mediaType: string,
  fps: number,
  width: number,
  height: number,
  duration: number, // in millis
}

export type ResourceInfo = {
  sizeInBytes: number,
  hash: string
}

export type Resource = {
  resourceId: string,
  bucketId: String,
  uploader: string,
  uploadTimestamp: number,
  userMeta: ResourceUserMeta,
  contentType: String,
  resourceMeta: ResourceMeta,
  resourceInfo: ResourceInfo,
  urls: ResourceUrls,
  clips: Array<Clip>,
}

export type Clip = {
  resourceId: string,
  range: [number, number],
  index: number,
  urls: string[],
  tags: string[]
}

export type ResourceUserMeta = {
  tags: string[]
  title: string
  description?: string
}

export type ResourceSelection = {
  query?: string
  playlist?: string
  tag?: string
  duration?: [number?, number?]
  minimumQuality: number
  sort: Sort
}

export type MediaView = 'grid' | 'list'

export type SearchResult = {
  total: number
  results: Resource[]
  tags: string[]
}

export type Resolution = {
  value: number,
  label: string
}

export type Range = {
  start: number,
  end: number
}

export type Sort = {
  field: string
  direction: SortDirection
}

export type SortDirection = 'asc' | 'desc'
export type Columns = 'auto' | number

export type Prefs = {
  showSidebar: boolean
  showTitles: boolean
  showDuration: boolean
  showDates: boolean
  showResolution: boolean
  gallery_columns: Columns
}
