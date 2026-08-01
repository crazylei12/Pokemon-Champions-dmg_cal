import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudDetail {
  build() { Stack() { BattleHudElementContent({ element: 'DETAIL' }) }.width('100%').height('100%') }
}
