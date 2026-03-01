import {Constants, SessionContext} from "../../api/Constants";
import {calculateColumns} from "../../api/Util";
import Dialog from "../common/Dialog";
import './ConfigMenu.scss';
import {useContext, useEffect, useState} from "react";
import {adminReComputeHashes, adminRefreshBucket, adminReindexBucket, adminRescanMetaData, getBuckets} from "../../api/generated";
import {useLocalStorage} from "usehooks-ts";
import {useTheme} from "../../ThemeContext";
import {ThemeSetting} from "../../api/Model";
import {BucketDto} from "../../api/generated/model/bucketDto";

const ConfigMenu = () => {

  // const [prefs, setPrefs] = useLocalStoragePrefs<Prefs>("prefs-v1", Constants.defaultPreferences)
  const [prefs, setPrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)
  const { themeSetting, setTheme } = useTheme();

  const columns = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((v) => {
    return { value: v, label: v.toString() }
  })

  const themeOptions: Array<{value: ThemeSetting, label: string}> = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ]

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }
  const session = useContext(SessionContext)

  return(
      <Dialog title = "Preferences">
        <div key="config-form" className="config-form">
          <div key="columns" className="form-section">
            <p key="header" className="form-label">Number of columns</p>
            <div key="content" className="form-content">
              <div className="column-select">
                <input
                  key="auto-radio"
                  style={{float: "left"}}
                  className="mr-1"
                  name="ncols-option"
                  type="radio"
                  value={0}
                  checked={prefs.gallery_columns === 'auto'}
                  onChange={(e) => {
                    if (prefs.gallery_columns !== 'auto') {
                      updatePrefs({gallery_columns: 'auto'})
                    }
                  }}
                />
                <span key="auto-label" style = {{float: "left"}}>auto</span>

                {/*<input style = {{float: "left"}} type="range" id="mySlider" min="0" max="100" step="25" value="0"/>*/}

                <input
                  key="custom-radio"
                  style = {{float: "left"}}
                  className="mr-1"
                  name="ncols-option"
                  type="radio"
                  value={0}
                  checked={prefs.gallery_columns !== 'auto'}
                  onChange={(e) => {
                    if (prefs.gallery_columns === 'auto')
                      updatePrefs({gallery_columns: calculateColumns()})
                  }}
                />

                <span key="custom-label" style={{float: "left"}}>custom</span>
                <select
                  key="custom-value"
                  name="ncols"
                  value={prefs.gallery_columns}
                  onChange={(e) => {
                    updatePrefs({gallery_columns: parseInt(e.target.value)})
                  }}>
                  {
                    columns.map((v, index) => {
                      return <option
                        key={`value-${index}`}
                        value={v.value}
                        label={v.label}
                      />;
                    })
                  }
                </select>
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
          { session.isAdmin() && <AdminOptions /> }
        </div>
      </Dialog>
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