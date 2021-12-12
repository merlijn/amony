import { Constants } from "../../api/Constants";
import { Prefs } from "../../api/Model";
import { useCookiePrefs } from "../../api/ReactUtils";
import { calculateColumns } from "../../api/Util";
import * as config from "../../AppConfig.json";
import './ConfigMenu.scss';

const ConfigMenu = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs/v1", "/", Constants.defaultPreferences)

  const columns = [1, 2, 3, 4, 5, 6, 7].map((v) => {
    return { value: v, label: v.toString() }
  })

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }

  return(

      <div className="config-menu default-modal-dialog">
        <div key="config-title" className="config-title">Preferences</div>

        <div key="config-form" className="config-form">

          <div className="form-section">
            <p className="form-label">Number of columns</p>
            <div className="form-content">
              <input
                style={ { float: "left" } }
                className="mr-1"
                name="ncols-option"
                type="radio"
                value={0}
                checked={prefs.gallery_columns === 'auto'}
                onChange={(e) => {
                  if (prefs.gallery_columns > 0) {
                    updatePrefs({gallery_columns: 'auto' })
                  }
                }}
              /><span style={ { float: "left" } } >auto</span>
              <input
                style={ { float: "left" } }
                className="mr-1"
                name="ncols-option"
                type="radio"
                value={0}
                checked={prefs.gallery_columns > 0}
                onChange={(e) => {
                  if (prefs.gallery_columns === 'auto')
                    updatePrefs({ gallery_columns: calculateColumns()} )
                }}
              /><span style={ { float: "left" } } >other</span>
              <select name="ncols" onChange={(e) => { updatePrefs( { gallery_columns: e.target.value }) } }>
                {
                  columns.map((v) => {
                    return <option
                      selected={ (prefs.gallery_columns === 'auto' && v.value === calculateColumns()) || prefs.gallery_columns === v.value }
                      value={v.value}
                      label={v.label}
                    />;
                  })
                }
              </select>
            </div>
          </div>

          <div className="form-section">
            <p className="form-label">Show info bar</p>
            <div className="form-content">
              <input
                type="checkbox"
                checked={ prefs.showTitles }
                onChange={(e) => { updatePrefs( { showTitles: !prefs.showTitles }) } }
              />
            </div>
          </div>

          <div className="form-section">
            <p className="form-label">Show video duration</p>
            <div className="form-content">
              <input
                type="checkbox"
                checked={ prefs.showDuration }
                onChange={(e) => { updatePrefs( { showDuration: !prefs.showDuration }) } }
              />
            </div>
          </div>
          <div className="form-section">
            <p className="form-label">Show dates</p>
            <div className="form-content">
              <input
                type="checkbox"
                checked={ prefs.showDates }
                onChange={ (e) => { updatePrefs( { showDates: !prefs.showDates }) } }
              />
            </div>
          </div>
          {
            config["enable-video-menu"] &&
              <div className="form-section">
                <p className="form-label">Show video menu</p>
                <div className="form-content">
                  <input
                    type="checkbox"
                    checked={ prefs.showMenu }
                    onChange={(e) => { updatePrefs( { showMenu: !prefs.showMenu }) } }
                  />
                </div>
              </div>
          }
        </div>

      </div>
  )
}

export default ConfigMenu