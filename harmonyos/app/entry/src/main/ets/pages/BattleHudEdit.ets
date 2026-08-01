import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudEdit {
  build() { Stack() { BattleHudElementContent({ element: 'EDIT' }) }.width('100%').height('100%') }
}
