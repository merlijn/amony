import {Constants, SessionContext} from "../../api/Constants";
import {clampGridColumns, maxGridColumns} from "../../api/Util";
import DialogWindow from "../common/DialogWindow";
import './ConfigMenu.scss';
import {useContext, useEffect, useState} from "react";
import {adminReComputeHashes, adminRefreshBucket, adminReindexBucket, adminRescanMetaData, getBuckets} from "../../api/generated";
import {useLocalStorage} from "usehooks-ts";
import {useTheme} from "../../ThemeContext";
import {GridAspectRatio, GridOrientation, ThemeSetting} from "../../api/Model";
import {BucketDto} from "../../api/generated/model/bucketDto";
import * as Tabs from "@radix-ui/react-tabs";

const ConfigMenu = () => {

  // const [prefs, setPrefs] = useLocalStoragePrefs<Prefs>("prefs-v1", Constants.defaultPreferences)
  const [prefs, setPrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)
  const { themeSetting, setTheme } = useTheme();
  const [maxColumns, setMaxColumns] = useState(() => maxGridColumns())

  useEffect(() => {
    const updateMaxColumns = () => setMaxColumns(maxGridColumns())
    updateMaxColumns()
    window.addEventListener('resize', updateMaxColumns)
    return () => window.removeEventListener('resize', updateMaxColumns)
  }, [])

  const themeOptions: Array<{value: ThemeSetting, label: string}> = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ]

  const aspectRatioOptions: Array<{value: GridAspectRatio, label: string}> = [
    { value: '2/1', label: '2 / 1' },
    { value: '16/9', label: '16 / 9' },
    { value: '15/10', label: '15 / 10' },
    { value: '5/4', label: '5 / 4' },
    { value: '1/1', label: '1 / 1' },
  ]

  const orientationOptions: Array<{value: GridOrientation, label: string}> = [
    { value: 'landscape', label: 'Landscape' },
    { value: 'portrait', label: 'Portrait' },
  ]

  const gridAspectRatio = prefs.gridAspectRatio ?? '16/9'
  const gridOrientation = prefs.gridOrientation ?? 'landscape'
  const galleryColumns = clampGridColumns(
    typeof prefs.gallery_columns === 'number' ? prefs.gallery_columns : Constants.defaultPreferences.gallery_columns,
    window.innerWidth
  )

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }
  const session = useContext(SessionContext)

  return(
      <DialogWindow>
        <Tabs.Root defaultValue="gridview">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="gridview">GridView</Tabs.Trigger>
            {session.isAdmin() && <Tabs.Trigger className="tab-trigger" value="admin">Admin</Tabs.Trigger>}
          </Tabs.List>

          <Tabs.Content className="tab-content" value="gridview">
            <div key="config-form" className="config-form">
              <div key="columns" className="form-section">
                <p key="header" className="form-label">Grid size</p>
                <div key="content" className="form-content">
                  <div className="column-slider">
                    <span className="column-slider-label">Less</span>
                    <input
                      type="range"
                      min={1}
                      max={maxColumns}
                      step={1}
                      value={galleryColumns}
                      onChange={(e) => {
                        updatePrefs({gallery_columns: parseInt(e.target.value, 10)})
                      }}
                    />
                    <span className="column-slider-label">More</span>
                  </div>
                </div>
              </div>

              <div key="aspect-ratio" className="form-section">
                <p key="header" className="form-label">Grid aspect ratio</p>
                <div key="content" className="form-content">
                  <div className="aspect-ratio-select">
                    <select
                      name="aspect-ratio"
                      value={gridAspectRatio}
                      onChange={(e) => {
                        updatePrefs({gridAspectRatio: e.target.value as GridAspectRatio})
                      }}
                    >
                      {aspectRatioOptions.map((option) => (
                        <option key={option.value} value={option.value} label={option.label} />
                      ))}
                    </select>
                    <div className="orientation-select">
                      {orientationOptions.map((option) => (
                        <label key={option.value} className="orientation-option">
                          <input
                            type="radio"
                            name="orientation-option"
                            value={option.value}
                            checked={gridOrientation === option.value}
                            onChange={() => updatePrefs({gridOrientation: option.value})}
                          />
                          <span>{option.label}</span>
                        </label>
                      ))}
                    </div>
                  </div>
                </div>
              </div>

              <div key="theme" className="form-section">
                <p key="header" className="form-label">Theme</p>
                <div key="content" className="form-content">
                  <div className="theme-select">
                    {themeOptions.map((option) => (
                      <label key={option.value} className="theme-option">
                        <input
                          type="radio"
                          name="theme-option"
                          value={option.value}
                          checked={themeSetting === option.value}
                          onChange={() => setTheme(option.value)}
                        />
                        <span>{option.label}</span>
                      </label>
                    ))}
                  </div>
                </div>
              </div>

              <div key="info-bar" className="form-section">
              <p key="header" className="form-label">Show info bar</p>
                <div key="content" className="form-content">
                  <input
                    type="checkbox"
                    checked={prefs.showTitles}
                    onChange={(e) => {
                      updatePrefs({showTitles: !prefs.showTitles})
                    }}
                  />
                </div>
              </div>

              <div key="duration" className="form-section">
                <p key="header" className="form-label">Show video duration</p>
                <div key="content" className="form-content">
                  <input
                      type="checkbox"
                      checked={prefs.showDuration}
                      onChange={(e) => {
                        updatePrefs({showDuration: !prefs.showDuration})
                      }}
                  />
                </div>
              </div>
              <div key="dates" className="form-section">
                <p key="header" className="form-label">Show dates</p>
                <div key="content" className="form-content">
                  <input
                      type="checkbox"
                      checked = { prefs.showDates }
                      onChange={(e) => {
                        updatePrefs({showDates: !prefs.showDates})
                      }}
                  />
                </div>
              </div>
              <div key="resolution" className="form-section">
                <p key="header" className="form-label">Show resolution</p>
                <div key="content" className="form-content">
                  <input
                      type="checkbox"
                      checked = { prefs.showResolution }
                      onChange = {(e) => {
                        updatePrefs({showResolution: !prefs.showResolution})
                      }}
                  />
                </div>
              </div>
            </div>
          </Tabs.Content>

          {session.isAdmin() && (
            <Tabs.Content className="tab-content" value="admin">
              <div key="config-form" className="config-form">
                <AdminOptions />
              </div>
            </Tabs.Content>
          )}
        </Tabs.Root>
      </DialogWindow>
  )
}

const AdminOptions = () => {
  const [buckets, setBuckets] = useState<BucketDto[]>([]);
  const [selectedBucket, setSelectedBucket] = useState<string>('');

  useEffect(() => {
    getBuckets().then((data) => {
      setBuckets(data);
      if (data.length > 0) {
        setSelectedBucket(data[0].bucketId);
      }
    });
  }, []);

  return(
    <>
      <div key="bucket-select" className="form-section">
        <p key="header" className="form-label">Bucket</p>
        <div key="content" className="form-content">
          <select
            value={selectedBucket}
            onChange={(e) => setSelectedBucket(e.target.value)}
          >
            {buckets?.map((bucket: BucketDto) => (
              <option key={bucket.bucketId} value={bucket.bucketId}>
                {bucket.bucketId}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div key="refresh-bucket" className="form-section">
        <p key="header" className="form-label">Refresh resources</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminRefreshBucket({'bucketId': selectedBucket})
          }}>Go
          </button>
        </div>
      </div>
      <div key="reindex-bucket" className="form-section">
        <p key="header" className="form-label">Reindex resources</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminReindexBucket({'bucketId': selectedBucket})
          }}>Go
          </button>
        </div>
      </div>
      <div key="rescan-meta-bucket" className="form-section">
        <p key="header" className="form-label">Rescan metadata</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminRescanMetaData({'bucketId': selectedBucket})
          }}>Go
          </button>
        </div>
      </div>
      <div key="re-compute-hashes-bucket" className="form-section">
        <p key="header" className="form-label">ReCompute hashes</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminReComputeHashes({'bucketId': selectedBucket})
          }}>Go
          </button>
        </div>
      </div>
    </>
  )
}

export default ConfigMenu