import { Constants } from "../../api/Constants";
import { Prefs } from "../../api/Model";
import { useCookiePrefs } from "../../api/ReactUtils";
import { calculateColumns } from "../../api/Util";
import Dialog from "../common/Dialog";
import './ConfigMenu.scss';

const ConfigMenu = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs/v1", "/", Constants.defaultPreferences)

  const columns = [1, 2, 3, 4, 5, 6, 7].map((v) => {
    return { value: v, label: v.toString() }
  })

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }

  return(
      <Dialog title = "Preferences">
        <div key = "config-form" className="config-form">
          <div key="columns" className = "form-section">
            <p key = "header" className = "form-label">Number of columns</p>
            <div key = "content" className = "form-content">
              <div className = "column-select">
                <input
                  key       = "auto-radio"
                  style     = { { float: "left" } }
                  className = "mr-1"
                  name      = "ncols-option"
                  type      = "radio"
                  value     = { 0 }
                  checked   = { prefs.gallery_columns === 'auto'}
                  onChange = { (e) => {
                    if (prefs.gallery_columns > 0) {
                      updatePrefs({gallery_columns: 'auto' })
                    }
                  }}
                />
                <span key="auto-label" style={ { float: "left" } } >auto</span>
                <input
                  key       = "custom-radio"
                  style     = { { float: "left" } }
                  className = "mr-1"
                  name      = "ncols-option"
                  type      = "radio"
                  value     = { 0 }
                  checked   = { prefs.gallery_columns > 0}
                  onChange  = { (e) => {
                    if (prefs.gallery_columns === 'auto')
                      updatePrefs({ gallery_columns: calculateColumns()} )
                  }}
                />
                <span key="custom-label" style={ { float: "left" } } >other</span>
                <select key="custom-value" name="ncols" onChange={(e) => { updatePrefs( { gallery_columns: e.target.value }) } }>
                  {
                    columns.map((v, index) => {
                      return <option
                        key      = { `value-${index}` }
                        selected = { (prefs.gallery_columns === 'auto' && v.value === calculateColumns()) || prefs.gallery_columns === v.value }
                        value    = {v.value}
                        label    = {v.label}
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
                checked={ prefs.showTitles }
                onChange={(e) => { updatePrefs( { showTitles: !prefs.showTitles }) } }
              />
            </div>
          </div>

          <div key="duration" className="form-section">
            <p key="header" className="form-label">Show video duration</p>
            <div key="content" className="form-content">
              <input
                type="checkbox"
                checked={ prefs.showDuration }
                onChange={(e) => { updatePrefs( { showDuration: !prefs.showDuration }) } }
              />
            </div>
          </div>
          <div key="dates" className="form-section">
            <p key="header" className="form-label">Show dates</p>
            <div key="content" className="form-content">
              <input
                type="checkbox"
                checked={ prefs.showDates }
                onChange={ (e) => { updatePrefs( { showDates: !prefs.showDates }) } }
              />
            </div>
          </div>
        </div>
      </Dialog>
  )
}

export default ConfigMenu