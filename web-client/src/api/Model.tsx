export type MediaUrls = {
  originalResourceUrl: string,
  thumbnailUrl: string,
  previewThumbnailsUrl?: string,
}

export type MediaInfo = {
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

export type Media = {
  id: string,
  uploader: string,
  uploadTimestamp: number,
  // the media info of the originally uploaded file
  meta: MediaUserMeta,
  mediaInfo: MediaInfo,
  resourceInfo: ResourceInfo,
  urls: MediaUrls,
  highlights: Array<Fragment>,
}

export type Fragment = {
  media_id: string,
  range: [number, number],
  index: number,
  urls: string[],
  tags: string[]
}

export type MediaUserMeta = {
  tags: string[]
  title: string
  comment?: string
}

export type MediaSelection = {
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
  videos: Media[]
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
  gallery_columns: Columns
}
