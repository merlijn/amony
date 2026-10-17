import {Constants, SessionContext} from "../../api/Constants";
import DialogWindow from "../common/DialogWindow";
import InfoTable, {InfoRow} from "../common/InfoTable";
import './ConfigMenu.scss';
import {useContext} from "react";
import {useLocalStorage} from "usehooks-ts";
import {useTheme} from "../../ThemeContext";
import {GridAspectRatio, GridOrientation, ThemeSetting} from "../../api/Model";
import * as Tabs from "@radix-ui/react-tabs";
import ChoiceOption from "../common/ChoiceOption";
import BucketsConfig from "./BucketsConfig";

const ConfigMenu = () => {

  // const [prefs, setPrefs] = useLocalStoragePrefs<Prefs>("prefs-v1", Constants.defaultPreferences)
  const [prefs, setPrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)
  const { themeSetting, setTheme } = useTheme();

  const themeOptions: Array<{value: ThemeSetting, label: string}> = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ]

  const aspectRatioOptions: Array<{value: GridAspectRatio, label: string}> = [
    { value: '2/1', label: '2 / 1' },
    { value: '16/9', label: '16 / 9' },
    { value: '3/2', label: '3 / 2' },
    { value: '5/4', label: '5 / 4' },
    { value: '1/1', label: '1 / 1' },
    { value: 'golden', label: 'Golden ratio' },
  ]

  const orientationOptions: Array<{value: GridOrientation, label: string}> = [
    { value: 'landscape', label: 'Landscape' },
    { value: 'portrait', label: 'Portrait' },
  ]

  const gridAspectRatio = prefs.gridAspectRatio ?? Constants.defaultPreferences.gridAspectRatio
  const gridOrientation = prefs.gridOrientation ?? 'landscape'
  const gridCellWidth = typeof prefs.gridCellWidth === 'number'
    ? prefs.gridCellWidth
    : Constants.defaultPreferences.gridCellWidth

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }
  const session = useContext(SessionContext)

  return(
      <DialogWindow>
        <Tabs.Root defaultValue="gridview">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="gridview">GridView</Tabs.Trigger>
            {session.isAdmin() && <Tabs.Trigger className="tab-trigger" value="buckets">Buckets</Tabs.Trigger>}
          </Tabs.List>

          <Tabs.Content className="tab-content" value="gridview">
            <InfoTable className="config-form">
              <InfoRow label="Grid size">
                <div className="column-slider">
                  <span className="column-slider-label">Small</span>
                  <input
                    type="range"
                    min={Constants.minGridCellWidth}
                    max={Constants.maxGridCellWidth}
                    value={gridCellWidth}
                    onChange={(e) => {
                      updatePrefs({gridCellWidth: parseInt(e.target.value, 10)})
                    }}
                  />
                  <span className="column-slider-label">Large</span>
                </div>
              </InfoRow>

              <InfoRow label="Grid aspect ratio">
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
                      <ChoiceOption
                        key={option.value}
                        type="radio"
                        name="orientation-option"
                        value={option.value}
                        checked={gridOrientation === option.value}
                        onChange={() => updatePrefs({gridOrientation: option.value})}
                        label={option.label}
                      />
                    ))}
                  </div>
                </div>
              </InfoRow>

              <InfoRow label="Theme">
                <div className="theme-select">
                  {themeOptions.map((option) => (
                    <ChoiceOption
                      key={option.value}
                      type="radio"
                      name="theme-option"
                      value={option.value}
                      checked={themeSetting === option.value}
                      onChange={() => setTheme(option.value)}
                      label={option.label}
                    />
                  ))}
                </div>
              </InfoRow>

              <InfoRow label="Show info bar">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.showTitles}
                  onChange={() => updatePrefs({showTitles: !prefs.showTitles})}
                />
              </InfoRow>

              <InfoRow label="Show video duration">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.showDuration}
                  onChange={() => updatePrefs({showDuration: !prefs.showDuration})}
                />
              </InfoRow>

              <InfoRow label="Show dates">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.showDates}
                  onChange={() => updatePrefs({showDates: !prefs.showDates})}
                />
              </InfoRow>

              <InfoRow label="Show resolution">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.showResolution}
                  onChange={() => updatePrefs({showResolution: !prefs.showResolution})}
                />
              </InfoRow>
            </InfoTable>
          </Tabs.Content>

          {session.isAdmin() && (
            <Tabs.Content className="tab-content" value="buckets">
              <BucketsConfig />
            </Tabs.Content>
          )}
        </Tabs.Root>
      </DialogWindow>
  )
}

export default ConfigMenu
