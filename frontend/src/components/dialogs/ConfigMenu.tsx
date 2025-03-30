import {Constants, SessionContext} from "../../api/Constants";
import {calculateColumns} from "../../api/Util";
import Dialog from "../common/Dialog";
import './ConfigMenu.scss';
import {useContext} from "react";
import {adminReComputeHashes, adminRefreshBucket, adminReindexBucket, adminRescanMetaData} from "../../api/generated";
import {useLocalStorage} from "usehooks-ts";

const ConfigMenu = () => {

  // const [prefs, setPrefs] = useLocalStoragePrefs<Prefs>("prefs-v1", Constants.defaultPreferences)
  const [prefs, setPrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)

  const columns = [1, 2, 3, 4, 5, 6, 7].map((v) => {
    return { value: v, label: v.toString() }
  })

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
                  checked  = { prefs.gallery_columns === 'auto' }
                  onChange = { (e) => {
                    if ( prefs.gallery_columns !== 'auto') {
                      updatePrefs( {gallery_columns: 'auto'} )
                    }
                  }}
                />
                <span key="auto-label" style={{float: "left"}}>auto</span>

                <input
                  key="custom-radio"
                  style={{float: "left"}}
                  className="mr-1"
                  name="ncols-option"
                  type="radio"
                  value = { 0 }
                  checked = { prefs.gallery_columns !== 'auto' }
                  onChange={(e) => {
                    if (prefs.gallery_columns === 'auto')
                      updatePrefs({gallery_columns: calculateColumns()})
                  }}
                />
                <span key="custom-label" style={{float: "left"}}>other</span>
                <select
                  key      = "custom-value"
                  name     = "ncols"
                  value    = { prefs.gallery_columns }
                  onChange = { (e) => {
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
  return(
    <>
      <div key="refresh-bucket" className="form-section">
        <p key="header" className="form-label">Refresh resources</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminRefreshBucket({'bucketId': 'media'})
          }}>Go
          </button>
        </div>
      </div>
      <div key="reindex-bucket" className="form-section">
        <p key="header" className="form-label">Reindex resources</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminReindexBucket({'bucketId': 'media'})
          }}>Go
          </button>
        </div>
      </div>
      <div key="rescan-meta-bucket" className="form-section">
        <p key="header" className="form-label">Rescan metadata</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminRescanMetaData({'bucketId': 'media'})
          }}>Go
          </button>
        </div>
      </div>
      <div key="re-compute-hashes-bucket" className="form-section">
        <p key="header" className="form-label">ReCompute hashes</p>
        <div key="content" className="form-content">
          <button onClick={() => {
            adminReComputeHashes({'bucketId': 'media'})
          }}>Go
          </button>
        </div>
      </div>
    </>
  )
}

export default ConfigMenu